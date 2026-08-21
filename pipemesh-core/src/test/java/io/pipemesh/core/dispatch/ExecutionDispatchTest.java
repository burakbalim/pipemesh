package io.pipemesh.core.dispatch;

import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.ExecutionHandle;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.execution.RecoveryScheduler;
import io.pipemesh.core.execution.StartMode;
import io.pipemesh.core.execution.TestClock;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.memory.InMemoryApprovalStore;
import io.pipemesh.core.state.memory.InMemoryExecutionLeases;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two instances, one queue. What has to be true is not that they cooperate, but
 * that they cannot both take the same work (§28).
 */
class ExecutionDispatchTest {

    private static final String NOTIFY = """
            {
              "id": "notify", "version": "1.0", "entry": "done",
              "steps": [{"id": "done", "type": "terminal", "status": "COMPLETED"}]
            }
            """;

    /** Stops on its own, so a claimed execution stays claimable-looking. */
    private static final String REVIEW = """
            {
              "id": "review", "version": "1.0", "entry": "decide",
              "steps": [
                {"id": "decide", "type": "human_approval", "message": "ok?",
                 "onApproved": "done", "onRejected": "done"},
                {"id": "done", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    private final TestClock clock = new TestClock(java.time.Instant.parse("2026-08-21T09:00:00Z"));
    private final InMemoryStateStore stateStore = new InMemoryStateStore(clock);
    private final InMemoryExecutionLeases leases = new InMemoryExecutionLeases(stateStore, clock);
    private final StepExecutors executors = StepExecutors.of(
            new ApprovalStepExecutor(new InMemoryApprovalStore()), new TerminalStepExecutor());
    private final InMemoryWorkflowRegistry workflows =
            new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
    private final WorkflowExecutor executor = new WorkflowExecutor(stateStore, executors);

    private final DefaultWorkflowRuntime runtime = new DefaultWorkflowRuntime(
            workflows, stateStore, executor, null, StartMode.DISPATCHED);

    private ExecutionDispatcher dispatcher(String owner) {
        return new ExecutionDispatcher(
                workflows, stateStore, executor, leases, owner, Duration.ofMinutes(5), 10);
    }

    private final ExecutionDispatcher first = dispatcher("pod-a");
    private final ExecutionDispatcher second = dispatcher("pod-b");

    @AfterEach
    void stop() {
        first.close();
        second.close();
    }

    private ExecutionHandle enqueue(String workflow, String id) {
        workflows.register(new WorkflowDefinitionReader().read(workflow));
        return runtime.start(new ExecutionRequest(
                WorkflowId.of(id), ExecutionInput.empty(), OrganizationId.of("acme"), null));
    }

    private ExecutionStatus statusOf(ExecutionHandle handle) {
        return runtime.snapshot(handle.executionId()).orElseThrow().status();
    }

    private void settle() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(200);
    }

    @Test
    void aDispatchedStartReturnsBeforeTheWorkIsDone() {
        ExecutionHandle handle = enqueue(NOTIFY, "notify");

        assertEquals(ExecutionStatus.CREATED, handle.status(),
                "the caller is not made to wait for the workflow");
    }

    @Test
    void aDispatcherFinishesWhatTheCallerDidNot() throws InterruptedException {
        ExecutionHandle handle = enqueue(NOTIFY, "notify");

        assertEquals(1, first.dispatchOnce());
        settle();

        assertEquals(ExecutionStatus.COMPLETED, statusOf(handle));
    }

    @Test
    void onlyOneOfTwoDispatchersGetsTheSameExecution() {
        enqueue(REVIEW, "review");

        assertEquals(List.of(1, 0), List.of(first.dispatchOnce(), second.dispatchOnce()));
    }

    @Test
    void aClaimedExecutionIsInvisibleToTheNextRound() {
        enqueue(REVIEW, "review");
        first.dispatchOnce();

        assertEquals(0, first.dispatchOnce(), "already mine is not free");
    }

    @Test
    void anExpiredLeaseIsClaimableAgain() {
        enqueue(REVIEW, "review");
        assertEquals(1, first.dispatchOnce());

        clock.advance(Duration.ofMinutes(6));

        assertEquals(1, second.dispatchOnce(), "nobody renewed it");
    }

    @Test
    void aRenewedLeaseIsNotStolen() {
        enqueue(REVIEW, "review");
        first.dispatchOnce();

        clock.advance(Duration.ofMinutes(4));
        assertEquals(1, first.renewAll());
        clock.advance(Duration.ofMinutes(4));

        assertEquals(0, second.dispatchOnce(), "still being worked on");
    }

    @Test
    void aReleasedExecutionThatFinishedIsNotClaimedAgain() throws InterruptedException {
        enqueue(NOTIFY, "notify");
        first.dispatchOnce();
        settle();

        assertEquals(0, second.dispatchOnce(), "a finished execution is not work");
    }

    @Test
    void oneRoundCannotTakeMoreThanItsBatch() {
        workflows.register(new WorkflowDefinitionReader().read(REVIEW));
        for (int queued = 0; queued < 5; queued++) {
            runtime.start(new ExecutionRequest(
                    WorkflowId.of("review"), ExecutionInput.empty(),
                    OrganizationId.of("acme"), null));
        }

        ExecutionDispatcher small = new ExecutionDispatcher(
                workflows, stateStore, executor, leases, "pod-c", Duration.ofMinutes(5), 2);

        assertEquals(2, small.dispatchOnce());
        small.close();
    }

    @Test
    void anExecutionWaitingForAPersonIsNotClaimed() throws InterruptedException {
        ExecutionHandle handle = enqueue(REVIEW, "review");
        first.dispatchOnce();
        settle();
        assertEquals(ExecutionStatus.WAITING, statusOf(handle));

        clock.advance(Duration.ofMinutes(6));

        assertEquals(0, second.dispatchOnce(), "waiting is not stuck");
    }

    @Test
    void theInlineModeStillRunsTheWorkflowItself() {
        workflows.register(new WorkflowDefinitionReader().read(NOTIFY));
        DefaultWorkflowRuntime inline = new DefaultWorkflowRuntime(workflows, stateStore, executor);

        ExecutionHandle handle = inline.start(new ExecutionRequest(
                WorkflowId.of("notify"), ExecutionInput.empty(), OrganizationId.of("acme"), null));

        assertEquals(ExecutionStatus.COMPLETED, handle.status(),
                "the default must not have changed");
    }

    @Test
    void aLeaseIsNotPartOfWhatAnExecutionReports() {
        ExecutionHandle handle = enqueue(NOTIFY, "notify");
        first.dispatchOnce();

        String snapshot = runtime.snapshot(handle.executionId()).orElseThrow().toString();

        assertFalse(snapshot.contains("pod-a"), "who is driving is not execution state");
    }

    @Test
    void anOwnerThatLostItsLeaseStopsRenewingIt() {
        enqueue(REVIEW, "review");
        first.dispatchOnce();

        clock.advance(Duration.ofMinutes(6));
        assertEquals(1, second.dispatchOnce(), "taken over");

        assertEquals(0, first.renewAll(), "the old owner has nothing left to renew");
    }
    /** Stage 4: the schedule that already exists is the one dispatch runs on. */
    @Test
    void aDispatcherFitsTheScheduleRecoveryAlreadyUses() throws Exception {
        enqueue(NOTIFY, "notify");
        RecoveryScheduler.RecoveryPass pass = first::dispatchOnce;

        try (RecoveryScheduler scheduler = new RecoveryScheduler(
                pass, Duration.ofMillis(20), failure -> {
                })) {
            scheduler.start();
            settle();
        }

        assertEquals(0, second.dispatchOnce(), "the scheduled pass took it and finished it");
    }
}
