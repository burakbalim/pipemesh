package io.pipemesh.core.state.memory;

import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.state.StaleExecutionException;
import io.pipemesh.core.state.StateStore;
import io.pipemesh.core.state.StepRecord;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link StateStore} that forgets everything when the process ends.
 *
 * <p>Useful for tests and for a single-process embedding where durability is not
 * wanted. It is <em>not</em> the store that proves this project's thesis: an
 * execution that survives a restart needs a real database, and the restart test
 * must run against one.
 *
 * <p>It does enforce the two rules an implementation cannot get wrong — the
 * version check, and writing the step history entry together with the state.
 */
public final class InMemoryStateStore implements StateStore {

    private final Map<ExecutionId, ExecutionRecord> executions = new ConcurrentHashMap<>();
    private final Map<ExecutionId, List<StepRecord>> history = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryStateStore() {
        this(Clock.systemUTC());
    }

    public InMemoryStateStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public ExecutionRecord create(ExecutionRecord record) {
        long now = clock.millis();
        ExecutionRecord stored = stamped(record, 1, now, now);
        if (executions.putIfAbsent(record.executionId(), stored) != null) {
            throw new IllegalStateException("execution " + record.executionId() + " already exists");
        }
        return stored;
    }

    @Override
    public Optional<ExecutionRecord> find(ExecutionId executionId) {
        return Optional.ofNullable(executions.get(executionId));
    }

    @Override
    public ExecutionRecord advance(ExecutionRecord record, StepRecord step) {
        ExecutionRecord stored = stamped(
                record, record.version() + 1, record.createdAtEpochMillis(), clock.millis());
        boolean replaced = executions.replace(
                record.executionId(), expected(record), stored);
        if (!replaced) {
            throw new StaleExecutionException(record.executionId(), record.version());
        }
        history.computeIfAbsent(record.executionId(), key -> new ArrayList<>()).add(step);
        return stored;
    }

    @Override
    public List<ExecutionRecord> findStale(ExecutionStatus status, long untouchedSince, int limit) {
        return executions.values().stream()
                .filter(record -> record.status() == status)
                .filter(record -> record.updatedAtEpochMillis() < untouchedSince)
                .sorted(Comparator.comparingLong(ExecutionRecord::updatedAtEpochMillis))
                .limit(Math.max(0, limit))
                .toList();
    }

    public List<StepRecord> historyOf(ExecutionId executionId) {
        return List.copyOf(history.getOrDefault(executionId, List.of()));
    }

    private ExecutionRecord expected(ExecutionRecord record) {
        ExecutionRecord current = executions.get(record.executionId());
        if (current == null || current.version() != record.version()) {
            throw new StaleExecutionException(record.executionId(), record.version());
        }
        return current;
    }

    private ExecutionRecord stamped(
            ExecutionRecord record, long version, long createdAt, long updatedAt) {

        return new ExecutionRecord(
                record.executionId(),
                record.organization(),
                record.workflowId(),
                record.workflowVersion(),
                record.status(),
                record.currentStep(),
                record.variables(),
                record.traceContext(),
                version,
                createdAt,
                updatedAt,
                record.principal());
    }
}
