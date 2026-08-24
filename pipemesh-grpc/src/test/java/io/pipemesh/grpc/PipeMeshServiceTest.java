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
import io.pipemesh.proto.v1.ExecutionUpdate;
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
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.state.StateStore;
import io.pipemesh.core.state.StepRecord;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
    private WorkflowRuntime runtime;

    /** Armed by a test that wants something to happen mid-read; otherwise inert. */
    private final AtomicReference<Runnable> duringRead = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        StateStore stateStore = new ReadsCanBeInterrupted(new InMemoryStateStore(), duringRead);
        StepExecutors executors = StepExecutors.of(
                new ConditionStepExecutor(),
                new ApprovalStepExecutor(new InMemoryApprovalStore()),
                new TerminalStepExecutor());

        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        workflows.register(new WorkflowDefinitionReader().read(BOOKING));

        runtime = new DefaultWorkflowRuntime(
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

    /** Under a hundred, so it finishes without asking anybody. */
    private io.pipemesh.proto.v1.ExecutionHandle startCheap() {
        return client.startExecution(StartExecutionRequest.newBuilder()
                .setWorkflowId("venue_booking")
                .setOrganizationId("acme")
                .setInput(input("{\"price\":50}"))
                .build());
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

        // The two leading STEP_FINISHED entries are replayed: this watcher
        // arrived after the condition ran and the approval suspended, and a
        // cursor of zero means "I have seen nothing" (§30.1). Before replay the
        // watcher simply never learned they happened.
        assertEquals(
                List.of("STARTED", "STEP_FINISHED", "STEP_FINISHED",
                        "RESUMED", "STEP_FINISHED", "STEP_STARTED", "STEP_FINISHED", "FINISHED"),
                kinds,
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
                List.of("0:STARTED", "1:STEP_FINISHED", "2:STEP_FINISHED", "3:RESUMED",
                        "4:STEP_FINISHED", "5:STEP_STARTED", "6:STEP_FINISHED", "7:FINISHED"),
                numbered,
                "one counter for this stream: snapshot, then replay, then live");
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

        assertEquals(
                List.of("STEP_FINISHED", "STEP_FINISHED", "RESUMED",
                        "STEP_FINISHED", "STEP_FINISHED", "FINISHED"),
                kinds,
                "progress declined; the replayed history and everything else still delivered");
    }
    /**
     * Reads until the named kind arrives, so a test never drains a stream that
     * is waiting for a person — and never acts on the channel without reading
     * first, which is what stalls a blocking stub.
     */
    private List<ExecutionUpdate> drain(Iterator<ExecutionUpdate> updates, String until) {
        List<ExecutionUpdate> seen = new ArrayList<>();
        for (int read = 0; read < 30; read++) {
            ExecutionUpdate update = updates.next();
            seen.add(update);
            if (until.equals(update.getUpdateCase().name())) {
                return seen;
            }
        }
        throw new AssertionError("never reached " + until + ": " + seen.size() + " updates");
    }

    @Test
    void aFinishedExecutionCanStillBeReadBackInFull() {
        var handle = startCheap();

        Iterator<ExecutionUpdate> updates = client.watchExecution(
                WatchExecutionRequest.newBuilder()
                        .setExecutionId(handle.getExecutionId()).build());

        List<String> kinds = new ArrayList<>();
        while (updates.hasNext()) {
            kinds.add(updates.next().getUpdateCase().name());
        }

        assertEquals("STARTED", kinds.get(0), "where things stood on arrival");
        assertTrue(kinds.contains("STEP_FINISHED"),
                "the history is what happened, and it is still there: " + kinds);
    }

    @Test
    void aCursorSkipsWhatTheClientAlreadySaw() {
        var handle = startCheap();

        Iterator<ExecutionUpdate> everything = client.watchExecution(
                WatchExecutionRequest.newBuilder()
                        .setExecutionId(handle.getExecutionId()).build());
        long steps = 0;
        while (everything.hasNext()) {
            if (everything.next().getUpdateCase() == ExecutionUpdate.UpdateCase.STEP_FINISHED) {
                steps++;
            }
        }
        assertTrue(steps > 0, "there was something to skip");

        Iterator<ExecutionUpdate> resumed = client.watchExecution(
                WatchExecutionRequest.newBuilder()
                        .setExecutionId(handle.getExecutionId())
                        .setFromStep(steps)
                        .build());

        List<String> kinds = new ArrayList<>();
        while (resumed.hasNext()) {
            kinds.add(resumed.next().getUpdateCase().name());
        }

        assertEquals(List.of("STARTED"), kinds,
                "already seen, so nothing but the arrival snapshot: " + kinds);
    }

    /** The point of the whole thing: a watcher that arrives late is not blind. */
    @Test
    void aWatcherThatArrivesAfterTheStepsStillLearnsAboutThem() {
        var waiting = startExpensive();

        Iterator<ExecutionUpdate> updates = client.watchExecution(
                WatchExecutionRequest.newBuilder()
                        .setExecutionId(waiting.getExecutionId()).build());

        List<ExecutionUpdate> seen = drain(updates, "STEP_FINISHED");

        assertEquals("STARTED", seen.get(0).getUpdateCase().name());
        assertEquals("check_price", seen.get(seen.size() - 1).getStepFinished().getStepId(),
                "the step that ran before anybody was listening");
    }

    @Test
    void aCursorPastTheEndAsksForNothingRatherThanFailing() {
        var handle = startCheap();

        Iterator<ExecutionUpdate> updates = client.watchExecution(
                WatchExecutionRequest.newBuilder()
                        .setExecutionId(handle.getExecutionId())
                        .setFromStep(9_999)
                        .build());

        List<String> kinds = new ArrayList<>();
        while (updates.hasNext()) {
            kinds.add(updates.next().getUpdateCase().name());
        }

        assertEquals(List.of("STARTED"), kinds);
    }

    /** Replay is an addition; a live watcher still sees everything live. */
    @Test
    void aWatcherWithNoCursorStillSeesEverythingLive() {
        var waiting = startExpensive();

        Iterator<ExecutionUpdate> updates = client.watchExecution(
                WatchExecutionRequest.newBuilder()
                        .setExecutionId(waiting.getExecutionId()).build());
        // The suspension itself happened before this watcher existed, so it
        // arrives as a replayed step rather than as a live event. Reading one is
        // still what matters here: acting on the channel without draining it is
        // what stalls a blocking stub.
        drain(updates, "STEP_FINISHED");

        approve(waiting.getExecutionId());

        List<String> kinds = new ArrayList<>();
        while (updates.hasNext()) {
            kinds.add(updates.next().getUpdateCase().name());
        }

        assertEquals("FINISHED", kinds.get(kinds.size() - 1), kinds.toString());
    }

    /**
     * The window this stream used to have a hole in.
     *
     * <p>A watcher read a snapshot and then subscribed. Anything published in
     * between belonged to neither — taken after the snapshot, published before
     * the subscription — and vanished, leaving a sequence that still looked
     * perfectly continuous. The client landing in that window is precisely the
     * one acting on what the snapshot just told it, which is what makes the
     * window worth closing rather than making smaller.
     *
     * <p>Reproduced rather than raced: the approval is delivered while the
     * snapshot is being read, so the update lands mid-window every time.
     */
    @Test
    void losesNothingPublishedWhileTheSnapshotIsBeingRead() {
        String executionId = startExpensive().getExecutionId();
        duringRead.set(() -> approve(executionId));

        // With the window open this stream never ends: the lost update is the one
        // that finishes the execution, so nothing ever tells the watcher to stop.
        // A deadline turns that into a failure instead of a hung build.
        List<String> kinds = new ArrayList<>();
        try {
            client.withDeadlineAfter(10, TimeUnit.SECONDS)
                    .watchExecution(WatchExecutionRequest.newBuilder()
                            .setExecutionId(executionId).build())
                    .forEachRemaining(update -> kinds.add(update.getUpdateCase().name()));
        } catch (StatusRuntimeException timedOut) {
            fail("the stream never ended, so something published mid-window was lost: " + kinds);
        }

        assertTrue(kinds.contains("RESUMED"),
                "the resume published while the snapshot was read went missing: " + kinds);
    }

    /**
     * Delegates every read and write, and lets a test interrupt one read.
     *
     * <p>Reaching in at the store rather than at the runtime because the service
     * needs the concrete {@link DefaultWorkflowRuntime} to scope a caller, so a
     * runtime wrapped for a test would be refused before it proved anything.
     */
    private record ReadsCanBeInterrupted(StateStore delegate, AtomicReference<Runnable> once)
            implements StateStore {

        @Override
        public ExecutionRecord create(ExecutionRecord record) {
            return delegate.create(record);
        }

        @Override
        public Optional<ExecutionRecord> find(ExecutionId executionId) {
            Optional<ExecutionRecord> found = delegate.find(executionId);
            Runnable armed = once.getAndSet(null);
            if (armed != null) {
                armed.run();
            }
            return found;
        }

        @Override
        public ExecutionRecord advance(ExecutionRecord record, StepRecord step) {
            return delegate.advance(record, step);
        }

        @Override
        public List<ExecutionRecord> findStale(
                io.pipemesh.core.execution.ExecutionStatus status, long untouchedSince, int limit) {
            return delegate.findStale(status, untouchedSince, limit);
        }

        @Override
        public List<StepRecord> historyOf(ExecutionId executionId) {
            return delegate.historyOf(executionId);
        }
    }
}
