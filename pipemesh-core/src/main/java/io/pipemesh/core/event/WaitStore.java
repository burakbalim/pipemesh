package io.pipemesh.core.event;

import java.util.List;
import java.util.Optional;

/**
 * Which executions are waiting, and for what.
 *
 * <p>Written before the execution suspends, for the same reason an approval is:
 * an event that arrives in the moment between deciding to wait and being on
 * record as waiting would find nobody, and the execution would wait forever for
 * something that already happened.
 */
public interface WaitStore {

    /** Idempotent: re-entering the same step files the same wait, not a second one. */
    PendingWait register(PendingWait wait);

    /** Every execution still waiting for this exact event — all of them, not the first. */
    List<PendingWait> waitingFor(EventKey key);

    Optional<PendingWait> find(String waitId);

    /**
     * Marks a wait as answered.
     *
     * @return empty when it was already settled, which is how the same event
     *         delivered twice moves an execution once
     */
    Optional<PendingWait> settle(String waitId, PendingWait.Status outcome);

    /** Waits whose deadline has passed, so nothing sits in WAITING unnoticed. */
    List<PendingWait> expiredBy(long epochMillis, int limit);
}
