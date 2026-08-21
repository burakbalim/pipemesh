package io.pipemesh.core.execution;

import io.pipemesh.core.observability.CompositeExecutionObserver;
import io.pipemesh.core.observability.ExecutionEvent;
import io.pipemesh.core.observability.ExecutionObserver;
import io.pipemesh.core.observability.TraceContext;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.state.StaleExecutionException;
import io.pipemesh.core.state.StateStore;
import io.pipemesh.core.state.StepRecord;
import io.pipemesh.core.workflow.ExecutionGraph;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.WorkflowRegistry;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Picks up executions that a process left running when it died.
 *
 * <p>An execution waiting for a person is fine — it is persisted and something
 * will resume it. An execution that was mid-step when its process was killed is
 * not: it sits in {@code RUNNING} and nobody owns it. This finds those and drives
 * them again.
 *
 * <p><b>It cannot know whether the owner is really gone.</b> A long step still
 * running looks exactly like a dead one. Two separate mechanisms make being wrong
 * survivable:
 *
 * <ul>
 *   <li>the store's version check means only one of the two writers can advance
 *       the execution — a mistaken sweep wastes work, it does not corrupt state;
 *   <li>{@link StepExecutor#repeatable} means a step that may already have had an
 *       effect is never run a second time. Those executions stop for a person
 *       instead.
 * </ul>
 *
 * <p>Nothing here decides <em>when</em> to sweep — {@link RecoveryScheduler} does,
 * and the same schedule can drive an {@code ExecutionDispatcher}.
 *
 * <p>Leases did not make this redundant. A lease expires when nobody renews it,
 * which says the process stopped; this looks at when the execution row was last
 * written, which says the <em>work</em> stopped. A driver wedged inside one step
 * goes on renewing happily while advancing nothing, and only this notices.
 */
public final class RecoverySweeper {

    /** Must exceed the longest step timeout, or a slow model call looks abandoned. */
    public static final Duration DEFAULT_THRESHOLD = Duration.ofMinutes(5);

    public static final int DEFAULT_BATCH = 50;

    private final WorkflowRegistry workflows;
    private final StateStore stateStore;
    private final WorkflowExecutor executor;
    private final StepExecutors executors;
    private final Clock clock;
    private final Duration threshold;
    private final int batch;
    private final ExecutionObserver observer;

    public RecoverySweeper(
            WorkflowRegistry workflows,
            StateStore stateStore,
            WorkflowExecutor executor,
            StepExecutors executors) {

        this(workflows, stateStore, executor, executors,
                Clock.systemUTC(), DEFAULT_THRESHOLD, DEFAULT_BATCH, ExecutionObserver.NONE);
    }

    public RecoverySweeper(
            WorkflowRegistry workflows,
            StateStore stateStore,
            WorkflowExecutor executor,
            StepExecutors executors,
            Clock clock,
            Duration threshold,
            int batch,
            ExecutionObserver observer) {

        this.workflows = Objects.requireNonNull(workflows, "workflow registry");
        this.stateStore = Objects.requireNonNull(stateStore, "state store");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.threshold = Objects.requireNonNull(threshold, "threshold");
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be positive");
        }
        this.batch = batch;
        this.observer = CompositeExecutionObserver.guarded(
                Objects.requireNonNull(observer, "observer"));
    }

    /**
     * Runs one pass.
     *
     * @return how many executions were picked up, whether they went on to finish
     *         or were stopped as unrecoverable
     */
    public int sweep() {
        List<ExecutionRecord> orphans = stateStore.findStale(
                ExecutionStatus.RUNNING, clock.millis() - threshold.toMillis(), batch);

        int recovered = 0;
        for (ExecutionRecord orphan : orphans) {
            if (recover(orphan)) {
                recovered++;
            }
        }
        return recovered;
    }

    private boolean recover(ExecutionRecord orphan) {
        Optional<ExecutionGraph> graph = workflows.find(orphan.workflowId(), orphan.workflowVersion());
        if (graph.isEmpty()) {
            // The workflow was unregistered while an execution of it was in flight.
            // Nothing can drive it, and leaving it in RUNNING forever helps no one.
            return stop(orphan, "execution.unknown_workflow",
                    "workflow '" + orphan.workflowId() + "' is no longer registered");
        }

        Step step = graph.get().stepAt(orphan.currentStep());
        if (!executors.forType(step.type()).repeatable(step, contextOf(orphan))) {
            return stop(orphan, "execution.unrecoverable",
                    "step '" + step.id() + "' may already have taken effect and cannot be repeated");
        }

        try {
            observer.executionRecovered(eventOf(orphan));
            executor.drive(graph.get(), orphan);
            return true;
        } catch (StaleExecutionException someoneElseHasIt) {
            // The owner was alive after all, or another sweeper got there first.
            // Either way this pass has nothing to do.
            return false;
        }
    }

    private boolean stop(ExecutionRecord orphan, String code, String message) {
        long now = clock.millis();
        ExecutionRecord failed = new ExecutionRecord(
                orphan.executionId(),
                orphan.organization(),
                orphan.workflowId(),
                orphan.workflowVersion(),
                ExecutionStatus.FAILED,
                orphan.currentStep(),
                orphan.variables(),
                orphan.traceContext(),
                orphan.version(),
                orphan.createdAtEpochMillis(),
                orphan.updatedAtEpochMillis(),
                orphan.principal());

        StepRecord entry = new StepRecord(
                orphan.executionId(),
                orphan.currentStep(),
                io.pipemesh.core.workflow.StepType.of("recovery"),
                StepRecord.StepOutcome.FAILED,
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode().put("code", code).put("message", message),
                "", "", 0L, 0L, 0L, now, now,
                JsonNodeFactory.instance.objectNode(), 1);

        try {
            stateStore.advance(failed, entry);
            observer.executionFinished(eventOf(failed));
            return true;
        } catch (StaleExecutionException someoneElseHasIt) {
            return false;
        }
    }

    /** The question "may this be repeated?" is about a step in an execution. */
    private io.pipemesh.core.execution.ExecutionContext contextOf(ExecutionRecord record) {
        return new io.pipemesh.core.execution.ExecutionContext(
                record.executionId(),
                record.organization(),
                record.workflowId(),
                record.workflowVersion(),
                record.currentStep(),
                record.variables(),
                record.traceContext(),
                record.principal());
    }

    private ExecutionEvent eventOf(ExecutionRecord record) {
        return new ExecutionEvent(
                record.executionId(),
                record.organization(),
                record.workflowId(),
                record.workflowVersion(),
                record.status(),
                record.currentStep(),
                TraceContext.parse(record.traceContext()).orElse(null),
                clock.millis(),
                record.createdAtEpochMillis(),
                record.updatedAtEpochMillis());
    }
}
