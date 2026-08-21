package io.pipemesh.grpc;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityId;
import io.pipemesh.core.capability.CapabilityKind;
import io.pipemesh.core.capability.InMemoryCapabilityRegistry;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.WorkflowRuntime;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.CapabilityStepExecutor;
import io.pipemesh.core.intent.DefaultIntentResolver;
import io.pipemesh.core.intent.IntentDefinition;
import io.pipemesh.core.intent.IntentId;
import io.pipemesh.core.intent.IntentRegistry;
import io.pipemesh.core.model.InMemoryModelRegistry;
import io.pipemesh.core.prompt.InMemoryPromptRegistry;
import io.pipemesh.core.execution.step.ConditionStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.memory.InMemoryApprovalStore;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;

import java.util.List;

/**
 * A runtime on a real port, for the SDK tests in other languages to call.
 *
 * <p>Started as a child process the way a Python or Node test would start any
 * server, so what those tests exercise is the wire and not a mock of it.
 *
 * <p>Prints the bound port on stdout and nothing else — the caller reads that line
 * to know where to connect.
 */
public final class TestRuntimeServer {

    private static final String BOOKING = """
            {
              "id": "venue_booking", "version": "1.0", "entry": "check_price",
              "steps": [
                {"id": "check_price", "type": "condition", "expression": "$.input.price > 100",
                 "onTrue": "approval", "onFalse": "booked"},
                {"id": "approval", "type": "human_approval", "message": "Book this venue?",
                 "onApproved": "booked", "onRejected": "cancelled"},
                {"id": "booked", "type": "terminal", "status": "COMPLETED"},
                {"id": "cancelled", "type": "terminal", "status": "CANCELLED"}
              ]
            }
            """;

    /**
     * A workflow whose only step is somebody else's business code.
     *
     * <p>It retries, because a worker connecting a moment after the call arrives
     * is ordinary rather than exceptional.
     */
    private static final String DISCOUNT = """
            {
              "id": "discount_check", "version": "1.0", "entry": "discount",
              "steps": [
                {"id": "discount", "type": "capability", "capability": "calculate_discount",
                 "input": "$.input", "output": "discount", "next": "done",
                 "retry": {"maxAttempts": 4, "initialDelay": "200ms"}},
                {"id": "done", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    /**
     * The same workflow at two versions, ending differently on purpose: the
     * final status is how a client in another language can tell which graph ran.
     */
    private static final String POLICY_V1 = """
            {
              "id": "policy_check", "version": "1.0", "entry": "done",
              "steps": [{"id": "done", "type": "terminal", "status": "COMPLETED"}]
            }
            """;

    private static final String POLICY_V2 = """
            {
              "id": "policy_check", "version": "2.0", "entry": "done",
              "steps": [{"id": "done", "type": "terminal", "status": "CANCELLED"}]
            }
            """;

    public static void main(String[] args) throws Exception {
        ExecutionUpdateBroker broker = new ExecutionUpdateBroker();
        InMemoryStateStore stateStore = new InMemoryStateStore();
        WorkerRegistry workers = new WorkerRegistry();

        InMemoryCapabilityRegistry capabilities = new InMemoryCapabilityRegistry().register(
                new CapabilityDescriptor(
                        CapabilityId.of("calculate_discount"), "Tier-based discount",
                        CapabilityKind.APPLICATION, "billing-team", "1.0", List.of(), null, null,
                        JsonNodeFactory.instance.objectNode()
                                .put("type", "worker")
                                .put("capability", "calculate_discount")));

        StepExecutors executors = StepExecutors.of(
                new ConditionStepExecutor(),
                new ApprovalStepExecutor(new InMemoryApprovalStore()),
                new CapabilityStepExecutor(capabilities,
                        List.of(new WorkerCapabilityProvider(workers))),
                new TerminalStepExecutor());

        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        workflows.register(new WorkflowDefinitionReader().read(BOOKING));
        workflows.register(new WorkflowDefinitionReader().read(DISCOUNT));
        workflows.register(new WorkflowDefinitionReader().read(POLICY_V1));
        workflows.register(new WorkflowDefinitionReader().read(POLICY_V2));

        IntentRegistry knownIntents = IntentRegistry.of(List.of(
                new IntentDefinition(IntentId.of("book_venue"), WorkflowId.of("venue_booking"),
                        "The user wants to book a venue", List.of("book a venue", "mekan ayır")),
                new IntentDefinition(IntentId.of("check_discount"), WorkflowId.of("discount_check"),
                        "The user asks what discount applies", List.of("discount", "indirim"))));

        WorkflowRuntime runtime = new DefaultWorkflowRuntime(
                workflows, stateStore, new WorkflowExecutor(stateStore, executors, broker),
                new DefaultIntentResolver(
                        knownIntents, new InMemoryModelRegistry(), new InMemoryPromptRegistry()));

        PipeMeshServer server =
                new PipeMeshServer(runtime, broker, 0, null, workers).start();
        System.out.println(server.port());
        System.out.flush();
        server.awaitTermination();
    }

    private TestRuntimeServer() {
    }
}
