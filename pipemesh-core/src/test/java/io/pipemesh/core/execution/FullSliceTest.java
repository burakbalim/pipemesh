package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.capability.CapabilityCall;
import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityId;
import io.pipemesh.core.capability.CapabilityKind;
import io.pipemesh.core.capability.CapabilityProvider;
import io.pipemesh.core.capability.CapabilityResult;
import io.pipemesh.core.capability.InMemoryCapabilityRegistry;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.CapabilityStepExecutor;
import io.pipemesh.core.execution.step.ConditionStepExecutor;
import io.pipemesh.core.execution.step.LlmStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.model.CompletionRequest;
import io.pipemesh.core.model.CompletionResponse;
import io.pipemesh.core.model.InMemoryModelRegistry;
import io.pipemesh.core.model.MessagingProvider;
import io.pipemesh.core.model.ModelId;
import io.pipemesh.core.prompt.InMemoryPromptRegistry;
import io.pipemesh.core.prompt.PromptId;
import io.pipemesh.core.state.StepRecord;
import io.pipemesh.core.state.memory.InMemoryApprovalStore;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slice's shape, end to end: LLM, condition, capability, approval, resume.
 *
 * <p>The model and the capability are stand-ins here — a real provider and a
 * real MCP server are the same two interfaces with a network behind them. What
 * this test pins down is that the workflow drives all of it from JSON and learns
 * nothing about how any of it is implemented.
 */
class FullSliceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BOOKING = """
            {
              "id": "venue_booking", "version": "1.0", "entry": "extract",
              "steps": [
                {"id": "extract", "type": "llm", "model": "fast",
                 "prompt": "venue_booking.extraction.v1",
                 "output": "request", "next": "validate"},

                {"id": "validate", "type": "condition", "expression": "$.request.valid == true",
                 "onTrue": "search_venue", "onFalse": "rejected"},

                {"id": "search_venue", "type": "capability", "capability": "venue_search",
                 "input": "$.request.location", "output": "venues", "next": "approval"},

                {"id": "approval", "type": "human_approval", "message": "Book this venue?",
                 "onApproved": "booked", "onRejected": "rejected"},

                {"id": "booked", "type": "terminal", "status": "COMPLETED"},
                {"id": "rejected", "type": "terminal", "status": "CANCELLED"}
              ]
            }
            """;

    /** Answers with whatever it was told to, and reports what it "spent". */
    private record ScriptedModel(String id, JsonNode answer, List<String> promptsSeen)
            implements MessagingProvider {

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            promptsSeen.add(request.prompt());
            return new CompletionResponse(answer, "scripted-model-1", 120, 45, 7);
        }
    }

    /** Stands in for an MCP server, and records what the workflow asked it. */
    private record ScriptedCapability(String type, List<JsonNode> inputsSeen)
            implements CapabilityProvider {

        @Override
        public CapabilityResult invoke(
                CapabilityDescriptor capability, JsonNode input, CapabilityCall call) {
            inputsSeen.add(input);
            return new CapabilityResult.Success(
                    JSON.createArrayNode().add(JSON.createObjectNode().put("name", "Kaleici Hall")));
        }
    }

    private final InMemoryStateStore stateStore = new InMemoryStateStore();
    private final InMemoryApprovalStore approvals = new InMemoryApprovalStore();
    private final List<String> promptsSeen = new ArrayList<>();
    private final List<JsonNode> capabilityInputs = new ArrayList<>();

    private WorkflowRuntime runtime;

    @BeforeEach
    void wireRuntime() {
        ObjectNode extracted = JSON.createObjectNode();
        extracted.put("valid", true);
        extracted.put("location", "Antalya");

        InMemoryModelRegistry models = new InMemoryModelRegistry().register(
                ModelId.of("fast"), new ScriptedModel("scripted", extracted, promptsSeen));

        InMemoryPromptRegistry prompts = new InMemoryPromptRegistry();
        prompts.register(PromptId.of("venue_booking.extraction.v1"),
                "Extract a booking request from: {{$.input.message}}");

        InMemoryCapabilityRegistry capabilities = new InMemoryCapabilityRegistry().register(
                new CapabilityDescriptor(
                        CapabilityId.of("venue_search"),
                        "Find suitable venues",
                        CapabilityKind.EXTERNAL,
                        "platform-team",
                        "1.0",
                        List.of("places.read"),
                        null,
                        null,
                        JsonNodeFactory.instance.objectNode()
                                .put("type", "mcp")
                                .put("server", "places")
                                .put("tool", "search")));

        StepExecutors executors = StepExecutors.of(
                new LlmStepExecutor(models, prompts),
                new ConditionStepExecutor(),
                new CapabilityStepExecutor(capabilities,
                        List.of(new ScriptedCapability("mcp", capabilityInputs))),
                new ApprovalStepExecutor(approvals),
                new TerminalStepExecutor());

        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        workflows.register(new WorkflowDefinitionReader().read(BOOKING));

        runtime = new DefaultWorkflowRuntime(
                workflows, stateStore, new WorkflowExecutor(stateStore, executors));
    }

    private ExecutionHandle startBooking() {
        try {
            return runtime.start(ExecutionRequest.of(
                    WorkflowId.of("venue_booking"),
                    new ExecutionInput((ObjectNode) JSON.readTree(
                            "{\"message\":\"Book a hall in Antalya tomorrow\"}"))));
        } catch (Exception malformed) {
            throw new IllegalStateException(malformed);
        }
    }

    @Test
    void runsTheWholeChainAndStopsAtTheApproval() {
        ExecutionHandle waiting = startBooking();

        assertEquals(ExecutionStatus.WAITING, waiting.status());
        assertEquals(StepId.of("approval"), waiting.currentStep());
    }

    @Test
    void rendersThePromptFromExecutionVariables() {
        startBooking();

        assertEquals(List.of("Extract a booking request from: Book a hall in Antalya tomorrow"),
                promptsSeen);
    }

    @Test
    void feedsTheCapabilityWhatTheModelExtracted() {
        startBooking();

        assertEquals(1, capabilityInputs.size());
        assertEquals("Antalya", capabilityInputs.get(0).asText());
    }

    @Test
    void keepsEveryStepsOutputAsAVariable() {
        ExecutionHandle waiting = startBooking();

        ExecutionSnapshot snapshot = runtime.snapshot(waiting.executionId()).orElseThrow();

        assertEquals(true, snapshot.variables().path("request").path("valid").asBoolean());
        assertEquals("Kaleici Hall", snapshot.variables().path("venues").get(0).path("name").asText());
    }

    @Test
    void recordsWhatTheModelCostOnTheStepThatSpentIt() {
        ExecutionHandle waiting = startBooking();

        StepRecord llmStep = stateStore.historyOf(waiting.executionId()).get(0);

        assertEquals("fast", llmStep.modelId());
        assertEquals("v1", llmStep.promptVersion());
        assertEquals(120, llmStep.inputTokens());
        assertEquals(45, llmStep.outputTokens());
    }

    @Test
    void recordsWhichCapabilityRanAndHowItWasReached() {
        ExecutionHandle waiting = startBooking();

        StepRecord capabilityStep = stateStore.historyOf(waiting.executionId()).get(2);

        assertEquals("venue_search",
                capabilityStep.attributes().path(StepAttributes.CAPABILITY_ID).asText());
        assertEquals("mcp",
                capabilityStep.attributes().path(StepAttributes.CAPABILITY_EXECUTION_TYPE).asText());
    }

    @Test
    void completesOnceThePersonApproves() {
        ExecutionHandle waiting = startBooking();

        ExecutionHandle finished = runtime.resume(waiting.executionId(),
                new ResumeSignal.Approval(
                        waiting.executionId().value() + ":approval", true, "burak", ""));

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertEquals(StepId.of("booked"), finished.currentStep());
    }

    @Test
    void neverTellsTheWorkflowHowTheCapabilityIsImplemented() {
        assertTrue(BOOKING.contains("\"capability\": \"venue_search\""));
        assertTrue(!BOOKING.contains("mcp"), "workflow JSON must not mention a transport");
    }
}
