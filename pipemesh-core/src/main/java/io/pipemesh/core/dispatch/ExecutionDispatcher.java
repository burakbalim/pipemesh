package io.pipemesh.core.dispatch;

import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.state.StaleExecutionException;
import io.pipemesh.core.state.StateStore;
import io.pipemesh.core.workflow.ExecutionGraph;
import io.pipemesh.core.workflow.WorkflowRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Takes executions nobody is driving and drives them (§28).
 *
 * <p>This is how a second runtime instance becomes useful rather than merely
 * present: both ask the store for work, and the store decides who gets what.
 * There is no assigner in the middle to fail or to disagree with the rows.
 *
 * <p>What it does <em>not</em> do is make two drivers safe by itself. A lease
 * arbitrates who should drive; the store's version check is what guarantees only
 * one of them can advance the execution if the arbitration was wrong.
 */
public final class ExecutionDispatcher implements AutoCloseable {

    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);
    public static final int DEFAULT_BATCH = 10;

    private final WorkflowRegistry workflows;
    private final StateStore stateStore;
    private final WorkflowExecutor executor;
    private final ExecutionLeases leases;
    private final String owner;
    private final Duration lease;
    private final int batch;
    private final ExecutorService drivers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<ExecutionId, ExecutionLease> inFlight = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    public ExecutionDispatcher(
            WorkflowRegistry workflows, StateStore stateStore,
            WorkflowExecutor executor, ExecutionLeases leases, String owner) {

        this(workflows, stateStore, executor, leases, owner, DEFAULT_LEASE, DEFAULT_BATCH);
    }

    /**
     * @param owner names this instance in the lease. A hostname or pod name makes
     *              "who is running this" answerable from the database alone.
     * @param lease how long a claim survives without renewal. Shorter means stuck
     *              work is picked up sooner; too short and a live driver is robbed
     *              of work it is still doing.
     */
    public ExecutionDispatcher(
            WorkflowRegistry workflows, StateStore stateStore, WorkflowExecutor executor,
            ExecutionLeases leases, String owner, Duration lease, int batch) {

        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.stateStore = Objects.requireNonNull(stateStore, "state store");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.lease = Objects.requireNonNull(lease, "lease duration");
        this.batch = batch;

        long every = Math.max(1, lease.toMillis() / 3);
        heartbeat.scheduleWithFixedDelay(this::renewAll, every, every, TimeUnit.MILLISECONDS);
    }

    /**
     * One round: claim what is free, drive it, give it back.
     *
     * @return how many executions this round took on
     */
    public int dispatchOnce() {
        List<ExecutionLease> claimed = leases.claim(owner, lease, batch);
        for (ExecutionLease taken : claimed) {
            inFlight.put(taken.executionId(), taken);
            drivers.execute(() -> driveAndRelease(taken));
        }
        return claimed.size();
    }

    /**
     * Drives one claimed execution to wherever it next stops.
     *
     * <p>Every ending releases the lease, including the failures: an execution
     * whose workflow is no longer deployed, or that another driver advanced from
     * under this one, must not stay claimed by an instance that has given up on
     * it.
     */
    private void driveAndRelease(ExecutionLease claim) {
        try {
            stateStore.find(claim.executionId())
                    .filter(record -> record.status().isDrivable())
                    .flatMap(this::graphAndRecord)
                    .ifPresent(work -> executor.drive(work.graph(), work.record()));
        } catch (StaleExecutionException lost) {
            // Somebody else moved it. Losing the race is the mechanism working.
        } finally {
            inFlight.remove(claim.executionId(), claim);
            leases.release(claim);
        }
    }

    private record Work(ExecutionGraph graph, ExecutionRecord record) {
    }

    /**
     * A record whose version was rolled back has nowhere to run. It keeps its
     * status rather than being failed here — the driver's job is to run what it
     * can, not to decide that an operational problem is the execution's fault.
     */
    private Optional<Work> graphAndRecord(ExecutionRecord record) {
        return workflows.find(record.workflowId(), record.workflowVersion())
                .map(graph -> new Work(graph, record));
    }

    /**
     * Extends every lease this instance still holds, on its own schedule so that
     * an embedder cannot forget to.
     *
     * <p>A heartbeat says the process is alive, not that the work is moving — a
     * step wedged forever would go on renewing. That is why {@code RecoverySweeper}
     * stays: it watches when the execution row was last <em>written</em>, which a
     * wedged step does not update. The two answer different questions.
     *
     * <p>A lease that could not be renewed has been taken by somebody else. This
     * stops renewing it; the store's version check is what stops the work.
     *
     * @return how many leases this instance still holds
     */
    public int renewAll() {
        int kept = 0;
        for (ExecutionLease claim : List.copyOf(inFlight.values())) {
            Optional<ExecutionLease> extended = leases.renew(claim, lease);
            if (extended.isEmpty()) {
                inFlight.remove(claim.executionId(), claim);
                continue;
            }
            inFlight.replace(claim.executionId(), claim, extended.get());
            kept++;
        }
        return kept;
    }

    /** Executions this instance is driving right now. */
    public int inFlight() {
        return inFlight.size();
    }

    @Override
    public void close() {
        heartbeat.shutdownNow();
        drivers.close();
    }
}
