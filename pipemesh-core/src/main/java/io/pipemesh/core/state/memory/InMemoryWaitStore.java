package io.pipemesh.core.state.memory;

import io.pipemesh.core.event.EventKey;
import io.pipemesh.core.event.PendingWait;
import io.pipemesh.core.event.WaitStore;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A {@link WaitStore} that forgets everything when the process ends. */
public final class InMemoryWaitStore implements WaitStore {

    private final Map<String, PendingWait> waits = new ConcurrentHashMap<>();

    @Override
    public PendingWait register(PendingWait wait) {
        PendingWait existing = waits.putIfAbsent(wait.waitId(), wait);
        return existing == null ? wait : existing;
    }

    @Override
    public List<PendingWait> waitingFor(EventKey key) {
        return waits.values().stream()
                .filter(wait -> wait.status() == PendingWait.Status.WAITING)
                .filter(wait -> wait.key().equals(key))
                .sorted(Comparator.comparingLong(PendingWait::waitingSinceEpochMillis))
                .toList();
    }

    @Override
    public Optional<PendingWait> find(String waitId) {
        return Optional.ofNullable(waits.get(waitId));
    }

    @Override
    public Optional<PendingWait> settle(String waitId, PendingWait.Status outcome) {
        PendingWait current = waits.get(waitId);
        if (current == null || current.status() != PendingWait.Status.WAITING) {
            return Optional.empty();
        }
        PendingWait settled = new PendingWait(current.waitId(), current.key(), current.executionId(),
                current.stepId(), outcome, current.waitingSinceEpochMillis(),
                current.expiresAtEpochMillis());

        return waits.replace(waitId, current, settled) ? Optional.of(settled) : Optional.empty();
    }

    @Override
    public List<PendingWait> expiredBy(long epochMillis, int limit) {
        return waits.values().stream()
                .filter(wait -> wait.status() == PendingWait.Status.WAITING)
                .filter(wait -> !wait.neverExpires())
                .filter(wait -> wait.expiresAtEpochMillis() < epochMillis)
                .sorted(Comparator.comparingLong(PendingWait::expiresAtEpochMillis))
                .limit(Math.max(0, limit))
                .toList();
    }
}
