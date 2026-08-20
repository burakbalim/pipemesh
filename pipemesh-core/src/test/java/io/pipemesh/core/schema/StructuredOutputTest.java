package io.pipemesh.core.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.LlmStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.model.CompletionRequest;
import io.pipemesh.core.model.CompletionResponse;
import io.pipemesh.core.model.InMemoryModelRegistry;
import io.pipemesh.core.model.MessagingProvider;
import io.pipemesh.core.model.ModelId;
import io.pipemesh.core.prompt.InMemoryPromptRegistry;
import io.pipemesh.core.prompt.PromptId;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.ExecutionGraph;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredOutputTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {"valid": {"type": "boolean"}, "location": {"type": "string"}},
              "required": ["valid", "location"]
            }
            """;

    private static final String WORKFLOW = """
            {
              "id": "extract_test", "version": "1.0", "entry": "extract",
              "steps": [
                {"id": "extract", "type": "llm", "model": "fast", "prompt": "p.v1",
                 "outputSchema": "venue-request", "output": "request", "next": "done"},
                {"id": "done", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    /** Answers with whatever it was handed, one answer per call. */
    private record ScriptedModel(List<String> answers, AtomicInteger call) implements MessagingProvider {

        @Override
        public String id() {
            return "scripted";
        }

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            int index = Math.min(call.getAndIncrement(), answers.size() - 1);
            return new CompletionResponse(parse(answers.get(index)), "scripted-1", 10, 5, 1);
        }

        private JsonNode parse(String answer) {
            try {
                return JSON.readTree(answer);
            } catch (Exception notJson) {
                return JSON.getNodeFactory().textNode(answer);
            }
        }
    }

    private final InMemoryStateStore stateStore = new InMemoryStateStore();

    private ExecutionRecord run(String workflowJson, List<String> answers) {
        InMemoryModelRegistry models = new InMemoryModelRegistry()
                .register(ModelId.of("fast"), new ScriptedModel(answers, new AtomicInteger()));

        InMemoryPromptRegistry prompts = new InMemoryPromptRegistry();
        prompts.register(PromptId.of("p.v1"), "Extract from {{$.input.message}}");

        InMemorySchemaRegistry schemas = new InMemorySchemaRegistry();
        try {
            schemas.register("venue-request", JSON.readTree(SCHEMA));
        } catch (Exception malformed) {
            throw new IllegalStateException(malformed);
        }

        StepExecutors executors = StepExecutors.of(
                new LlmStepExecutor(models, prompts, schemas), new TerminalStepExecutor());

        ExecutionGraph graph = new InMemoryWorkflowRegistry(new WorkflowCompiler(executors))
                .register(new WorkflowDefinitionReader().read(workflowJson));

        return new WorkflowExecutor(stateStore, executors).start(
                graph, ExecutionId.generate(),
                ExecutionRequest.of(WorkflowId.of("extract_test"), ExecutionInput.empty()));
    }

    private String failureCodeOf(ExecutionRecord record) {
        return stateStore.historyOf(record.executionId()).get(0).output().path("code").asText();
    }

    @Test
    void acceptsAnAnswerThatMatchesTheSchema() {
        ExecutionRecord finished = run(WORKFLOW,
                List.of("{\"valid\":true,\"location\":\"Antalya\"}"));

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertEquals("Antalya", finished.variables().path("request").path("location").asText());
    }

    @Test
    void refusesAnAnswerMissingARequiredField() {
        ExecutionRecord finished = run(WORKFLOW, List.of("{\"valid\":true}"));

        assertEquals(ExecutionStatus.FAILED, finished.status());
        assertEquals("llm.schema_violation", failureCodeOf(finished));
    }

    @Test
    void refusesAnAnswerWithAFieldOfTheWrongType() {
        ExecutionRecord finished = run(WORKFLOW,
                List.of("{\"valid\":\"yes please\",\"location\":\"Antalya\"}"));

        assertEquals(ExecutionStatus.FAILED, finished.status());
        assertEquals("llm.schema_violation", failureCodeOf(finished));
    }

    @Test
    void refusesAnAnswerThatIsNotJsonAtAll() {
        ExecutionRecord finished = run(WORKFLOW, List.of("I could not comply"));

        assertEquals(ExecutionStatus.FAILED, finished.status());
        assertEquals("llm.schema_violation", failureCodeOf(finished));
    }

    @Test
    void saysWhichFieldWasWrong() {
        ExecutionRecord finished = run(WORKFLOW, List.of("{\"valid\":true}"));

        String message = stateStore.historyOf(finished.executionId())
                .get(0).output().path("message").asText();

        assertTrue(message.contains("$.location"), message);
    }

    @Test
    void letsARetryFixAModelThatIgnoredTheShape() {
        String withRetry = WORKFLOW.replace(
                "\"output\": \"request\", \"next\": \"done\"",
                "\"output\": \"request\", \"next\": \"done\", "
                        + "\"retry\": {\"maxAttempts\": 2, \"initialDelay\": \"1ms\"}");

        ExecutionRecord finished = run(withRetry, List.of(
                "{\"valid\":true}",
                "{\"valid\":true,\"location\":\"Antalya\"}"));

        assertEquals(ExecutionStatus.COMPLETED, finished.status(),
                "a schema violation is worth one more attempt");
    }

    @Test
    void leavesAStepWithoutASchemaAlone() {
        String noSchema = WORKFLOW.replace("\"outputSchema\": \"venue-request\", ", "");

        ExecutionRecord finished = run(noSchema, List.of("just some prose"));

        assertEquals(ExecutionStatus.COMPLETED, finished.status(),
                "asking for no schema still means asking for nothing");
    }

    @Test
    void reportsASchemaNobodyRegistered() {
        String unknownSchema = WORKFLOW.replace("venue-request", "no-such-schema");

        ExecutionRecord finished = run(unknownSchema,
                List.of("{\"valid\":true,\"location\":\"Antalya\"}"));

        assertEquals(ExecutionStatus.FAILED, finished.status());
        assertEquals("llm.unknown_schema", failureCodeOf(finished));
    }

    @Test
    void acceptsASchemaWrittenInlineInTheStep() {
        String inline = WORKFLOW.replace("\"outputSchema\": \"venue-request\"",
                "\"outputSchema\": {\"type\": \"object\", \"required\": [\"location\"]}");

        ExecutionRecord finished = run(inline, List.of("{\"location\":\"Izmir\"}"));

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
    }
}
