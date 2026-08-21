package io.pipemesh.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.WorkflowRuntime;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.ConditionStepExecutor;
import io.pipemesh.core.intent.DefaultIntentResolver;
import io.pipemesh.core.intent.IntentDefinition;
import io.pipemesh.core.intent.IntentId;
import io.pipemesh.core.intent.IntentRegistry;
import io.pipemesh.core.model.InMemoryModelRegistry;
import io.pipemesh.core.prompt.InMemoryPromptRegistry;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.memory.InMemoryApprovalStore;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
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

    private PipeMeshServer server;
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
                workflows, stateStore, new WorkflowExecutor(stateStore, executors, broker),
                new DefaultIntentResolver(
                        IntentRegistry.of(List.of(new IntentDefinition(
                                IntentId.of("book_venue"), WorkflowId.of("venue_booking"),
                                "The user wants to book a venue",
                                List.of("book a venue")))),
                        new InMemoryModelRegistry(), new InMemoryPromptRegistry()));

        // A real socket rather than the in-process transport: the streaming calls
        // here are exactly what a client in another language makes, and the
        // in-process transport does not behave like a network when one blocking
        // stub both watches a stream and makes calls on the side.
        server = new PipeMeshServer(runtime, broker, 0).start();

        channel = ManagedChannelBuilder.forAddress("localhost", server.port())
                .usePlaintext()
                .build();
        client = PipeMeshGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.close();
    }

    private com.google.protobuf.Struct input(String json) {
        try {
            return JsonStructs.toStruct((ObjectNode) JSON.readTree(json));
        } catch (Exception malformed) {
            throw new IllegalArgumentException(malformed);
        }
    }

    private void approve(String executionId) {
        client.submitApproval(SubmitApprovalRequest.newBuilder()
                .setExecutionId(executionId)
                .setApprovalId(executionId + ":approval")
                .setApproved(true)
                .build());
    }

    private static String numbered(io.pipemesh.proto.v1.ExecutionUpdate update) {
        return update.getSequence() + ":" + update.getUpdateCase().name();
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
    void readsAMessageAndRunsWhatItAskedFor() {
        var handle = client.processMessage(ProcessMessageRequest.newBuilder()
                .setMessage("I would like to book a venue for Friday")
                .setOrganizationId("acme")
                .setInput(input("{\"price\":250}"))
                .build());

        assertEquals(ExecutionStatus.EXECUTION_STATUS_WAITING, handle.getStatus());
        assertEquals("approval", handle.getCurrentStepId());
    }

    @Test
    void recordsWhichIntentStartedTheExecution() {
        var handle = client.processMessage(ProcessMessageRequest.newBuilder()
                .setMessage("book a venue please")
                .setOrganizationId("acme")
                .setInput(input("{\"price\":250}"))
                .build());

        var variables = client.getExecution(GetExecutionRequest.newBuilder()
                .setExecutionId(handle.getExecutionId()).build()).getVariables();

        var intent = variables.getFieldsOrThrow("intent").getStructValue();
        assertEquals("book_venue", intent.getFieldsOrThrow("id").getStringValue());
        assertEquals("deterministic", intent.getFieldsOrThrow("resolvedBy").getStringValue());
    }

    @Test
    void saysItCouldNotTellWhatAMessageMeant() {
        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class,
                () -> client.processMessage(ProcessMessageRequest.newBuilder()
                        .setMessage("what is the weather like in Antalya")
                        .setOrganizationId("acme")
                        .build()));

        assertEquals(io.grpc.Status.Code.FAILED_PRECONDITION, failure.getStatus().getCode(),
                "the request was fine; the runtime could not tell what to do with it");
    }

    @Test
    void streamsWhatHappensToAnExecutionThatIsAlreadyWaiting() {
        var waiting = startExpensive();

        Iterator<io.pipemesh.proto.v1.ExecutionUpdate> updates =
                client.watchExecution(WatchExecutionRequest.newBuilder()
                        .setExecutionId(waiting.getExecutionId()).build());

        // Read the arrival snapshot before doing anything: a blocking stub only
        // pumps its callbacks while the caller is inside next(), so a stream left
        // untouched is a stream nobody is draining.
        List<String> kinds = new ArrayList<>();
        kinds.add(updates.next().getUpdateCase().name());

        approve(waiting.getExecutionId());

        while (updates.hasNext()) {
            kinds.add(updates.next().getUpdateCase().name());
        }

        assertEquals(
                List.of("STARTED", "RESUMED", "STEP_FINISHED",
                        "STEP_STARTED", "STEP_FINISHED", "FINISHED"), kinds,
                "the stream opens with where things stand and ends when the execution does");
    }

    @Test
    void opensTheStreamWithWhereTheExecutionAlreadyIs() {
        var waiting = startExpensive();

        var first = firstUpdateFor(waiting.getExecutionId());

        assertEquals(0, first.getSequence(), "sequence zero is the state on arrival");
        assertEquals(ExecutionStatus.EXECUTION_STATUS_WAITING,
                first.getStarted().getExecution().getStatus());
    }

    /**
     * Reads one update and hangs up.
     *
     * <p>Through a cancellable context, because a blocking server stream that is
     * simply abandoned leaves a thread parked on it — the reader never learns the
     * call is over, and a JVM with one of those in it will not exit.
     */
    private io.pipemesh.proto.v1.ExecutionUpdate firstUpdateFor(String executionId) {
        Context.CancellableContext watching = Context.current().withCancellation();
        try {
            return watching.call(() -> client.watchExecution(WatchExecutionRequest.newBuilder()
                    .setExecutionId(executionId).build()).next());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        } finally {
            watching.cancel(null);
        }
    }

    @Test
    void numbersTheUpdatesItSends() {
        var waiting = startExpensive();

        Iterator<io.pipemesh.proto.v1.ExecutionUpdate> updates =
                client.watchExecution(WatchExecutionRequest.newBuilder()
                        .setExecutionId(waiting.getExecutionId()).build());

        List<String> numbered = new ArrayList<>();
        numbered.add(numbered(updates.next()));

        approve(waiting.getExecutionId());

        while (updates.hasNext()) {
            numbered.add(numbered(updates.next()));
        }

        assertEquals(
                List.of("0:STARTED", "1:RESUMED", "2:STEP_FINISHED",
                        "3:STEP_STARTED", "4:STEP_FINISHED", "5:FINISHED"),
                numbered,
                "one counter, no gaps, and the snapshot is 0");
    }

    /**
     * The step being resumed gets no {@code step.started}: it started days ago,
     * and {@code resumed} is the event that says what just happened to it. Only
     * the steps that follow are starting now.
     */
    @Test
    void aResumedStepIsNotAnnouncedAsStarting() {
        var waiting = startExpensive();
        Iterator<io.pipemesh.proto.v1.ExecutionUpdate> updates =
                client.watchExecution(WatchExecutionRequest.newBuilder()
                        .setExecutionId(waiting.getExecutionId()).build());
        updates.next();

        approve(waiting.getExecutionId());

        List<String> started = new ArrayList<>();
        while (updates.hasNext()) {
            io.pipemesh.proto.v1.ExecutionUpdate update = updates.next();
            if (update.getUpdateCase()
                    == io.pipemesh.proto.v1.ExecutionUpdate.UpdateCase.STEP_STARTED) {
                started.add(update.getStepStarted().getStepId());
            }
        }

        assertEquals(List.of("booked"), started, "the approval step was already under way");
    }

    @Test
    void aWatcherCanDeclineProgressAndStillSeeWhatHappened() {
        var waiting = startExpensive();
        Iterator<io.pipemesh.proto.v1.ExecutionUpdate> updates =
                client.watchExecution(WatchExecutionRequest.newBuilder()
                        .setExecutionId(waiting.getExecutionId())
                        .addExclude(io.pipemesh.proto.v1.UpdateKind.UPDATE_KIND_PROGRESS)
                        .build());
        updates.next();

        approve(waiting.getExecutionId());

        List<String> kinds = new ArrayList<>();
        while (updates.hasNext()) {
            kinds.add(updates.next().getUpdateCase().name());
        }

        assertEquals(List.of("RESUMED", "STEP_FINISHED", "STEP_FINISHED", "FINISHED"), kinds,
                "progress declined, everything else still delivered");
    }
}
