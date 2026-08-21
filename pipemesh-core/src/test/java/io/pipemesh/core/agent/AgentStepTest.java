package io.pipemesh.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.pipemesh.core.capability.CapabilityCall;
import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityId;
import io.pipemesh.core.capability.CapabilityInvoker;
import io.pipemesh.core.capability.CapabilityKind;
import io.pipemesh.core.capability.CapabilityProvider;
import io.pipemesh.core.capability.CapabilityResult;
import io.pipemesh.core.capability.InMemoryCapabilityRegistry;
import io.pipemesh.core.capability.Principal;
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.StepAttributes;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.AgentStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.model.CompletionRequest;
import io.pipemesh.core.model.CompletionResponse;
import io.pipemesh.core.model.InMemoryModelRegistry;
import io.pipemesh.core.model.MessagingProvider;
import io.pipemesh.core.model.ModelId;
import io.pipemesh.core.prompt.InMemoryPromptRegistry;
import io.pipemesh.core.prompt.PromptId;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.state.StepRecord;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.ExecutionGraph;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model drives inside a fence the workflow built (§9.9). These tests are
 * mostly about the fence.
 */
class AgentStepTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String WORKFLOW = """
            {
              "id": "research", "version": "1.0", "entry": "investigate",
              "steps": [
                {"id": "investigate", "type": "agent", "model": "reasoning",
                 "prompt": "research.investigate.v1",
                 "capabilities": ["search_docs"],
                 "maxIterations": 4,
                 "output": "findings", "next": "done"},
                {"id": "done", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    /** Says whatever it was scripted to, one line per turn. */
    private record ScriptedModel(List<String> turns, AtomicInteger asked) implements MessagingProvider {

        @Override
        public String id() {
            return "scripted";
        }

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            int turn = Math.min(asked.getAndIncrement(), turns.size() - 1);
            return new CompletionResponse(parse(turns.get(turn)), "scripted-1", 20, 8, 1);
        }

        private JsonNode parse(String text) {
            try {
                return JSON.readTree(text);
            } catch (Exception notJson) {
                return JSON.getNodeFactory().textNode(text);
            }
        }
    }

    private record SearchProvider(String type, List<String> queries) implements CapabilityProvider {

        @Override
        public CapabilityResult invoke(CapabilityDescriptor capability, JsonNode input, CapabilityCall call) {
            queries.add(input.path("query").asText());
            return new CapabilityResult.Success(
                    JsonNodeFactory.instance.objectNode().put("hits", 2));
        }
    }

    private final InMemoryStateStore stateStore = new InMemoryStateStore();
    private final AtomicInteger asked = new AtomicInteger();
    private final List<String> queries = new ArrayList<>();

    private ExecutionRecord run(List<String> modelTurns, Principal caller, List<String> required) {
        InMemoryModelRegistry models = new InMemoryModelRegistry()
                .register(ModelId.of("reasoning"), new ScriptedModel(modelTurns, asked));

        InMemoryPromptRegistry prompts = new InMemoryPromptRegistry();
        prompts.register(PromptId.of("research.investigate.v1"),
                "You may use {{$.agent.capabilities}}. So far: {{$.agent.history}}");

        InMemoryCapabilityRegistry capabilities = new InMemoryCapabilityRegistry()
                .register(new CapabilityDescriptor(
                        CapabilityId.of("search_docs"), "", CapabilityKind.EXTERNAL, "team", "1.0",
                        required, null, null,
                        JsonNodeFactory.instance.objectNode().put("type", "http")))
                .register(new CapabilityDescriptor(
                        CapabilityId.of("delete_everything"), "", CapabilityKind.APPLICATION,
                        "team", "1.0", List.of(), null, null,
                        JsonNodeFactory.instance.objectNode().put("type", "http")));

        StepExecutors executors = StepExecutors.of(
                new AgentStepExecutor(models, prompts,
                        new CapabilityInvoker(capabilities, List.of(new SearchProvider("http", queries)))),
                new TerminalStepExecutor());

        ExecutionGraph graph = new InMemoryWorkflowRegistry(new WorkflowCompiler(executors))
                .register(new WorkflowDefinitionReader().read(WORKFLOW));

        return new WorkflowExecutor(stateStore, executors).start(
                graph, ExecutionId.generate(),
                new ExecutionRequest(WorkflowId.of("research"), ExecutionInput.empty(),
                        null, null, null, caller));
    }

    private ExecutionRecord run(List<String> modelTurns) {
        return run(modelTurns, Principal.SYSTEM, List.of());
    }

    private StepRecord agentStep(ExecutionRecord record) {
        return stateStore.historyOf(record.executionId()).get(0);
    }

    @Test
    void callsAToolAndThenAnswers() {
        ExecutionRecord finished = run(List.of(
                "{\"call\":{\"capability\":\"search_docs\",\"input\":{\"query\":\"pipemesh\"}}}",
                "{\"answer\":{\"summary\":\"found it\"}}"));

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertEquals(List.of("pipemesh"), queries);
        assertEquals("found it", finished.variables().path("findings").path("summary").asText());
    }

    @Test
    void answersWithoutCallingAnything() {
        ExecutionRecord finished = run(List.of("{\"answer\":{\"summary\":\"already knew\"}}"));

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertTrue(queries.isEmpty());
    }

    @Test
    void refusesACapabilityTheStepDidNotList() {
        ExecutionRecord finished = run(List.of(
                "{\"call\":{\"capability\":\"delete_everything\",\"input\":{}}}",
                "{\"answer\":{\"summary\":\"fine then\"}}"));

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertTrue(queries.isEmpty(), "a capability it was not given is not one it may discover");

        String history = agentStep(finished).attributes().path(StepAttributes.AGENT_HISTORY).toString();
        assertTrue(history.contains("delete_everything"), history);
        assertTrue(history.contains("is not one of the capabilities"), history);
    }

    @Test
    void aRefusedCallCostsATurnAndNothingElse() {
        ExecutionRecord finished = run(List.of(
                "{\"call\":{\"capability\":\"delete_everything\"}}",
                "{\"call\":{\"capability\":\"search_docs\",\"input\":{\"query\":\"ok\"}}}",
                "{\"answer\":{\"summary\":\"done\"}}"));

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertEquals(3, agentStep(finished).attributes().path(StepAttributes.AGENT_TURNS).asInt());
    }

    @Test
    void givesUpRatherThanCallingTheLastThingAnAnswer() {
        ExecutionRecord finished = run(List.of(
                "{\"call\":{\"capability\":\"search_docs\",\"input\":{\"query\":\"a\"}}}"));

        assertEquals(ExecutionStatus.FAILED, finished.status());
        assertEquals("agent.turns_exhausted", agentStep(finished).output().path("code").asText());
        assertEquals(4, queries.size(), "it used every turn it was given");
    }

    @Test
    void aMalformedTurnCostsATurnAndNothingElse() {
        ExecutionRecord finished = run(List.of(
                "I think I should search for something",
                "{\"answer\":{\"summary\":\"recovered\"}}"));

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
    }

    @Test
    void cannotReachACapabilityTheCallerMayNotUse() {
        ExecutionRecord finished = run(
                List.of("{\"call\":{\"capability\":\"search_docs\",\"input\":{\"query\":\"x\"}}}",
                        "{\"answer\":{\"summary\":\"gave up\"}}"),
                Principal.of("intern"),
                List.of("docs.read"));

        assertTrue(queries.isEmpty(), "an agent must not be a way around the permission check");

        String history = agentStep(finished).attributes().path(StepAttributes.AGENT_HISTORY).toString();
        assertTrue(history.contains("capability.forbidden"), history);
    }

    @Test
    void reportsWhatTheWholeLoopCost() {
        ExecutionRecord finished = run(List.of(
                "{\"call\":{\"capability\":\"search_docs\",\"input\":{\"query\":\"a\"}}}",
                "{\"answer\":{\"summary\":\"done\"}}"));

        StepRecord step = agentStep(finished);
        assertEquals(40, step.inputTokens(), "two turns of twenty");
        assertEquals(16, step.outputTokens());
        assertEquals(2, step.attributes().path(StepAttributes.AGENT_TURNS).asInt());
    }

    @Test
    void tellsTheModelWhatItHasAlreadyTried() {
        run(List.of(
                "{\"call\":{\"capability\":\"search_docs\",\"input\":{\"query\":\"first\"}}}",
                "{\"answer\":{\"summary\":\"done\"}}"));

        // Without the history every turn would repeat the first one.
        assertEquals(2, asked.get());
    }
}
