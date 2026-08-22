package io.pipemesh.core.state;

import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.ExecutionStatus;

import java.util.List;

import java.util.Optional;

/**
 * Durable execution state (§15) — the only reason a workflow can survive a
 * restart.
 *
 * <p>The hard rule an implementation must honour: {@link #advance} writes the
 * step history entry and the new execution state in <em>one</em> transaction.
 * Split them and a crash between the two either loses the step or replays it.
 *
 * <p>Equally hard, in the other direction: provider I/O (a model call, an MCP
 * invocation) must never happen inside that transaction. Core has no framework
 * to enforce this, so it is enforced by where the calls are made.
 */
public interface StateStore {

    ExecutionRecord create(ExecutionRecord record);

    Optional<ExecutionRecord> find(ExecutionId executionId);

    /**
     * Persists a completed step and the execution state it produced, atomically.
     *
     * @throws StaleExecutionException if the record's version is no longer current
     */
    ExecutionRecord advance(ExecutionRecord record, StepRecord step);

    /**
     * Executions left in {@code status} and untouched since {@code untouchedSince}.
     *
     * <p>How recovery finds work that nobody owns any more. There is no heartbeat
     * behind this — only the last time a row was written — so the answer is a
     * guess, and the caller has to be safe when the guess is wrong.
     *
     * @param limit how many to return at most, so one sweep cannot pull in an
     *              afternoon's worth of stuck work at once
     */
    List<ExecutionRecord> findStale(ExecutionStatus status, long untouchedSince, int limit);

    /**
     * Every step this execution has run, oldest first.
     *
     * <p>Append-only and ordered, which is what makes a position in it a cursor
     * anybody can agree on: "I have seen the first N" means the same thing on
     * every replica, unlike a stream's sequence number (§30.2). Replay is built
     * on that rather than on a second copy of the events.
     */
    List<StepRecord> historyOf(ExecutionId executionId);
}
