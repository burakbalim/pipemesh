package io.pipemesh.core.state.memory;

import io.pipemesh.core.dispatch.ExecutionLease;
import io.pipemesh.core.dispatch.ExecutionLeases;
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.state.ExecutionRecord;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leases held in memory, for tests and single-process embeddings.
 *
 * <p>Like {@link InMemoryStateStore}, it cannot prove anything about a crashed
 * process — everything it knows dies with the JVM. What it does prove is the
 * arbitration: two drivers asking at once, only one of them getting the work.
 */
public final class InMemoryExecutionLeases implements ExecutionLeases {

    private final InMemoryStateStore executions;
    private final Clock clock;
    private final Map<ExecutionId, ExecutionLease> held = new ConcurrentHashMap<>();

    public InMemoryExecutionLeases(InMemoryStateStore executions) {
        this(executions, Clock.systemUTC());
    }

    public InMemoryExecutionLeases(InMemoryStateStore executions, Clock clock) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<ExecutionLease> claim(String owner, Duration duration, int limit) {
        long now = clock.millis();
        List<ExecutionLease> taken = new ArrayList<>();

        for (ExecutionRecord runnable : oldestFirst()) {
            if (taken.size() >= limit) {
                break;
            }
            take(runnable.executionId(), owner, now, duration).ifPresent(taken::add);
        }
        return taken;
    }

    @Override
    public Optional<ExecutionLease> renew(ExecutionLease lease, Duration duration) {
        ExecutionLease extended = new ExecutionLease(
                lease.executionId(), lease.owner(), lease.token(),
                clock.millis() + duration.toMillis());

        return held.replace(lease.executionId(), lease, extended)
                ? Optional.of(extended)
                : Optional.empty();
    }

    @Override
    public void release(ExecutionLease lease) {
        held.remove(lease.executionId(), lease);
    }

    /** Oldest first, so a long queue drains rather than starving its own head. */
    private List<ExecutionRecord> oldestFirst() {
        return executions.runnable().stream()
                .sorted(Comparator.comparingLong(ExecutionRecord::updatedAtEpochMillis))
                .toList();
    }

    /**
     * {@code compute} is what makes this an arbitration rather than a race: the
     * check and the write happen under one lock per execution, so two drivers
     * asking at the same moment cannot both come away with it.
     */
    private Optional<ExecutionLease> take(
            ExecutionId executionId, String owner, long now, Duration duration) {

        ExecutionLease mine = new ExecutionLease(
                executionId, owner, UUID.randomUUID().toString(), now + duration.toMillis());

        ExecutionLease current = held.compute(executionId,
                (id, existing) -> existing == null || existing.expiredAt(now) ? mine : existing);

        return mine.equals(current) ? Optional.of(mine) : Optional.empty();
    }
}
