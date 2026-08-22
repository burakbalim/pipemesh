package io.pipemesh.core.dispatch;

import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.execution.StartMode;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.TestClock;
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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What an autoscaler is told, and what it must not be told.
 *
 * <p>The age of the queue is the delay somebody is experiencing. Two things
 * would ruin it: counting work that is already being driven, and counting an
 * execution that is waiting for a person.
 */
class BacklogTest {

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

    private final TestClock clock = new TestClock(Instant.parse("2026-08-22T09:00:00Z"));
    private final InMemoryStateStore stateStore = new InMemoryStateStore(clock);
    private final InMemoryExecutionLeases leases = new InMemoryExecutionLeases(stateStore, clock);
    private final StepExecutors executors = StepExecutors.of(
            new ApprovalStepExecutor(new InMemoryApprovalStore()), new TerminalStepExecutor());
    private final InMemoryWorkflowRegistry workflows =
            new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
    private final WorkflowExecutor executor = new WorkflowExecutor(stateStore, executors, clock, 100);
    private final DefaultWorkflowRuntime runtime = new DefaultWorkflowRuntime(
            workflows, stateStore, executor, null, StartMode.DISPATCHED);

    private void enqueue() {
        if (workflows.latest(WorkflowId.of("review")).isEmpty()) {
            workflows.register(new WorkflowDefinitionReader().read(REVIEW));
        }
        runtime.start(new ExecutionRequest(
                WorkflowId.of("review"), ExecutionInput.empty(), OrganizationId.of("acme"), null));
    }

    @Test
    void anEmptyQueueReportsZerosRatherThanNothing() {
        assertEquals(ExecutionLeases.Backlog.EMPTY, leases.backlog(),
                "a gauge that stops being reported reads as its last value, forever");
    }

    @Test
    void waitingWorkIsCountedAndAged() {
        enqueue();
        clock.advance(Duration.ofSeconds(30));

        ExecutionLeases.Backlog backlog = leases.backlog();

        assertEquals(1, backlog.size());
        assertEquals(30_000, backlog.oldestWaitingMillis());
    }

    @Test
    void theAgeIsTheOldestOneNotTheNewest() {
        enqueue();
        clock.advance(Duration.ofSeconds(30));
        enqueue();

        assertEquals(30_000, leases.backlog().oldestWaitingMillis(),
                "the front of the queue is what somebody is waiting behind");
    }

    /** Claimed work is running work. Counting it breaks the feedback loop. */
    @Test
    void claimedWorkIsNotWaitingWork() {
        enqueue();
        leases.claim("pod-a", Duration.ofMinutes(5), 10);

        assertEquals(ExecutionLeases.Backlog.EMPTY, leases.backlog());
    }

    @Test
    void anAbandonedClaimIsWaitingAgain() {
        enqueue();
        leases.claim("pod-a", Duration.ofMinutes(5), 10);

        clock.advance(Duration.ofMinutes(6));

        assertEquals(1, leases.backlog().size(), "nobody renewed it, so it is queued again");
    }

    /**
     * The one that would make this metric actively harmful: an execution that has
     * been waiting three days for a person would have an autoscaler adding
     * drivers forever, none of which can help.
     */
    @Test
    void anExecutionWaitingForAPersonIsNotABacklog() {
        enqueue();
        var claimed = leases.claim("pod-a", Duration.ofMinutes(5), 10);
        executor.drive(workflows.latest(WorkflowId.of("review")).orElseThrow(),
                stateStore.find(claimed.get(0).executionId()).orElseThrow());
        leases.release(claimed.get(0));

        clock.advance(Duration.ofDays(3));

        assertEquals(ExecutionLeases.Backlog.EMPTY, leases.backlog(),
                "that is a waiting person, not a queue");
    }
}
