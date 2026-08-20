package io.pipemesh.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
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
import io.pipemesh.proto.v1.ExecutionStatus;
import io.pipemesh.proto.v1.GetExecutionRequest;
import io.pipemesh.proto.v1.PipeMeshGrpc;
import io.pipemesh.proto.v1.ProcessMessageRequest;
import io.pipemesh.proto.v1.StartExecutionRequest;
import io.pipemesh.proto.v1.SubmitApprovalRequest;
import io.pipemesh.proto.v1.WatchExecutionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Called over a real gRPC channel with the generated stub, because what is worth
 * checking is the wire: a client written in another language sees exactly this.
 */
class PipeMeshServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BOOKING = """
            {
              "id": "venue_booking", "version": "1.0", "entry": "check_price",
              "steps": [
                {"id": "check_price", "type": "condition", "expression": "$.input.price > 100",
                 "onTrue": "approval", "onFalse": "booked"},
                {"id": "approval", "type": "human_approval", "message": "Book?",
                 "onApproved": "booked", "onRejected": "cancelled"},
                {"id": "booked", "type": "terminal", "status": "COMPLETED"},
                {"id": "cancelled", "type": "terminal", "status": "CANCELLED"}
              ]
            }
            """;

    private Server server;
    private ManagedChannel channel;
    private PipeMeshGrpc.PipeMeshBlockingStub client;
    private final ExecutionUpdateBroker broker = new ExecutionUpdateBroker();

    @BeforeEach
    void startServer() throws IOException {
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

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .addService(new PipeMeshService(runtime, broker))
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(name).build();
        client = PipeMeshGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private com.google.protobuf.Struct input(String json) {
        try {
            return JsonStructs.toStruct((ObjectNode) JSON.readTree(json));
        } catch (Exception malformed) {
            throw new IllegalArgumentException(malformed);
        }
    }

    private io.pipemesh.proto.v1.ExecutionHandle startExpensive() {
        return client.startExecution(StartExecutionRequest.newBuilder()
                .setWorkflowId("venue_booking")
                .setOrganizationId("acme")
                .setInput(input("{\"price\":250}"))
                .build());
    }

    @Test
    void startsAWorkflowAndReportsWhereItStopped() {
        var handle = startExpensive();

        assertEquals(ExecutionStatus.EXECUTION_STATUS_WAITING, handle.getStatus());
        assertEquals("approval", handle.getCurrentStepId());
        assertTrue(!handle.getExecutionId().isBlank());
    }

    @Test
    void carriesTheOrganizationAndVariablesAcrossTheWire() {
        var handle = startExpensive();

        var snapshot = client.getExecution(GetExecutionRequest.newBuilder()
                .setExecutionId(handle.getExecutionId()).build());

        assertEquals("acme", snapshot.getOrganizationId());
        assertEquals(250, snapshot.getVariables()
                .getFieldsOrThrow("input").getStructValue()
                .getFieldsOrThrow("price").getNumberValue(), 0.0);
    }

    @Test
    void resumesAWaitingExecution() {
        var waiting = startExpensive();

        var finished = client.submitApproval(SubmitApprovalRequest.newBuilder()
                .setExecutionId(waiting.getExecutionId())
                .setApprovalId(waiting.getExecutionId() + ":approval")
                .setApproved(true)
                .setDecidedBy("burak")
                .build());

        assertEquals(ExecutionStatus.EXECUTION_STATUS_COMPLETED, finished.getStatus());
        assertEquals("booked", finished.getCurrentStepId());
    }

    @Test
    void tellsAClientWhenTheExecutionIsNotThere() {
        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class,
                () -> client.getExecution(GetExecutionRequest.newBuilder()
                        .setExecutionId("no-such-execution").build()));

        assertEquals(io.grpc.Status.Code.NOT_FOUND, failure.getStatus().getCode());
    }

    @Test
    void tellsAClientWhenTheWorkflowIsNotRegistered() {
        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class,
                () -> client.startExecution(StartExecutionRequest.newBuilder()
                        .setWorkflowId("no_such_workflow").build()));

        assertEquals(io.grpc.Status.Code.NOT_FOUND, failure.getStatus().getCode());
    }

    @Test
    void saysPlainlyThatIntentResolutionIsNotBuiltYet() {
        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class,
                () -> client.processMessage(ProcessMessageRequest.newBuilder()
                        .setMessage("book me a hall").build()));

        assertEquals(io.grpc.Status.Code.UNIMPLEMENTED, failure.getStatus().getCode());
    }

    @Test
    void streamsWhatHappensToAnExecutionThatIsAlreadyWaiting() {
        var waiting = startExpensive();

        Iterator<io.pipemesh.proto.v1.ExecutionUpdate> updates =
                client.watchExecution(WatchExecutionRequest.newBuilder()
                        .setExecutionId(waiting.getExecutionId()).build());

        client.submitApproval(SubmitApprovalRequest.newBuilder()
                .setExecutionId(waiting.getExecutionId())
                .setApprovalId(waiting.getExecutionId() + ":approval")
                .setApproved(true)
                .build());

        List<String> kinds = new ArrayList<>();
        while (updates.hasNext()) {
            kinds.add(updates.next().getUpdateCase().name());
        }

        assertEquals(List.of("RESUMED", "STEP_FINISHED", "STEP_FINISHED", "FINISHED"), kinds,
                "the stream ends itself when the execution does");
    }

    @Test
    void numbersTheUpdatesItSends() {
        var waiting = startExpensive();

        Iterator<io.pipemesh.proto.v1.ExecutionUpdate> updates =
                client.watchExecution(WatchExecutionRequest.newBuilder()
                        .setExecutionId(waiting.getExecutionId()).build());

        client.submitApproval(SubmitApprovalRequest.newBuilder()
                .setExecutionId(waiting.getExecutionId())
                .setApprovalId(waiting.getExecutionId() + ":approval")
                .setApproved(true)
                .build());

        assertEquals(1, updates.next().getSequence());
        assertEquals(2, updates.next().getSequence());
    }
}
