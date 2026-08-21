package io.pipemesh.core.dispatch;

import io.pipemesh.core.execution.ExecutionId;

import java.util.Objects;

/**
 * One runtime instance's claim on driving one execution (§28).
 *
 * <p>Deliberately not part of {@code ExecutionRecord}: who is currently driving
 * is an operational fact about the deployment, not state of the execution. It
 * has no business appearing in a snapshot, in telemetry or on the wire.
 *
 * <p>{@code token} distinguishes two claims by the same owner across a restart —
 * a process that comes back with the same name must not be able to renew a lease
 * its previous life took out.
 */
public record ExecutionLease(
        ExecutionId executionId, String owner, String token, long expiresAtEpochMillis) {

    public ExecutionLease {
        Objects.requireNonNull(executionId, "execution id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(token, "token");
    }

    public boolean expiredAt(long epochMillis) {
        return epochMillis >= expiresAtEpochMillis;
    }
}
