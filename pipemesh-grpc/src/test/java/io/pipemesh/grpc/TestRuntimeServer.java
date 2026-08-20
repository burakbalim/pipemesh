package io.pipemesh.grpc;

import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.WorkflowRuntime;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.ConditionStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.memory.InMemoryApprovalStore;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;

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

    public static void main(String[] args) throws Exception {
        ExecutionUpdateBroker broker = new ExecutionUpdateBroker();
        InMemoryStateStore stateStore = new InMemoryStateStore();

        StepExecutors executors = StepExecutors.of(
                new ConditionStepExecutor(),
                new ApprovalStepExecutor(new InMemoryApprovalStore()),
                new TerminalStepExecutor());

        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        workflows.register(new WorkflowDefinitionReader().read(BOOKING));

        WorkflowRuntime runtime = new DefaultWorkflowRuntime(
                workflows, stateStore, new WorkflowExecutor(stateStore, executors, broker));

        PipeMeshServer server = new PipeMeshServer(runtime, broker, 0).start();
        System.out.println(server.port());
        System.out.flush();
        server.awaitTermination();
    }

    private TestRuntimeServer() {
    }
}
