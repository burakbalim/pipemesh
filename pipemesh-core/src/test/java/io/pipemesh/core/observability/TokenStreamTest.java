package io.pipemesh.core.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.LlmStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.model.CompletionChunk;
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
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenStreamTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String STREAMING = """
            {
              "id": "chat", "version": "1.0", "entry": "answer",
              "steps": [
                {"id": "answer", "type": "llm", "model": "fast", "prompt": "chat.v1",
                 "stream": true, "output": "reply", "next": "done"},
                {"id": "done", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    /** Hands over three pieces, the way a real endpoint would. */
    private record PiecemealModel() implements MessagingProvider {

        @Override
        public String id() {
            return "piecemeal";
        }

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            return new CompletionResponse(JSON.getNodeFactory().textNode("Antalya is warm"), "p", 9, 3, 1);
        }

        @Override
        public CompletionResponse stream(CompletionRequest request, Consumer<CompletionChunk> onChunk) {
            List<String> pieces = List.of("Antalya ", "is ", "warm");
            for (int index = 0; index < pieces.size(); index++) {
                onChunk.accept(new CompletionChunk(pieces.get(index), index));
            }
            return complete(request);
        }
    }

    private static final class TokenRecorder implements ExecutionObserver {

        final List<TokenEvent> tokens = new ArrayList<>();

        @Override
        public void tokenProduced(TokenEvent event) {
            tokens.add(event);
        }
    }

    private final InMemoryStateStore stateStore = new InMemoryStateStore();

    private ExecutionRecord run(String workflowJson, ExecutionObserver observer) {
        InMemoryModelRegistry models =
                new InMemoryModelRegistry().register(ModelId.of("fast"), new PiecemealModel());

        InMemoryPromptRegistry prompts = new InMemoryPromptRegistry();
        prompts.register(PromptId.of("chat.v1"), "Answer: {{$.input.question}}");

        StepExecutors executors = StepExecutors.of(
                new LlmStepExecutor(models, prompts, schemaId -> java.util.Optional.empty(), observer),
                new TerminalStepExecutor());

        ExecutionGraph graph = new InMemoryWorkflowRegistry(new WorkflowCompiler(executors))
                .register(new WorkflowDefinitionReader().read(workflowJson));

        return new WorkflowExecutor(stateStore, executors, observer).start(
                graph, ExecutionId.generate(),
                ExecutionRequest.of(WorkflowId.of("chat"), ExecutionInput.empty(),
                        OrganizationId.of("acme")));
    }

    @Test
    void handsTokensToWhoeverIsWatching() {
        TokenRecorder recorder = new TokenRecorder();

        run(STREAMING, recorder);

        assertEquals(List.of("Antalya ", "is ", "warm"),
                recorder.tokens.stream().map(TokenEvent::text).toList());
    }

    @Test
    void saysWhichStepAndWhichOrganizationEachTokenBelongsTo() {
        TokenRecorder recorder = new TokenRecorder();

        run(STREAMING, recorder);

        TokenEvent first = recorder.tokens.get(0);
        assertEquals(StepId.of("answer"), first.stepId());
        assertEquals(OrganizationId.of("acme"), first.organization());
        assertEquals(0, first.index());
    }

    @Test
    void stillEndsWithTheWholeAnswerInAVariable() {
        ExecutionRecord finished = run(STREAMING, new TokenRecorder());

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertEquals("Antalya is warm", finished.variables().path("reply").asText());
    }

    @Test
    void staysQuietForAStepThatDidNotAskToStream() {
        TokenRecorder recorder = new TokenRecorder();

        run(STREAMING.replace("\"stream\": true, ", ""), recorder);

        assertTrue(recorder.tokens.isEmpty());
    }

    @Test
    void anObserverThatIgnoresTokensIsUnaffected() {
        List<String> events = new ArrayList<>();
        ExecutionObserver oblivious = new ExecutionObserver() {

            @Override
            public void executionFinished(ExecutionEvent event) {
                events.add("finished");
            }
        };

        run(STREAMING, oblivious);

        assertEquals(List.of("finished"), events);
    }
}
