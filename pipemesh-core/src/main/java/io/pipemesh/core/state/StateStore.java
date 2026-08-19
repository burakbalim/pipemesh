package io.pipemesh.core.state;

import io.pipemesh.core.execution.ExecutionId;

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
}
