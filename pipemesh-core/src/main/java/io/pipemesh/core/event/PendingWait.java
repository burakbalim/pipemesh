package io.pipemesh.core.event;

import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.workflow.StepId;

import java.util.Objects;

/** An execution paused until something happens elsewhere (§9.7). */
public record PendingWait(
        String waitId,
        EventKey key,
        ExecutionId executionId,
        StepId stepId,
        Status status,
        long waitingSinceEpochMillis,
        long expiresAtEpochMillis) {

    public enum Status {
        WAITING,
        DELIVERED,
        EXPIRED
    }

    public PendingWait {
        Objects.requireNonNull(waitId, "wait id");
        Objects.requireNonNull(key, "event key");
        Objects.requireNonNull(executionId, "execution id");
        Objects.requireNonNull(stepId, "step id");
        Objects.requireNonNull(status, "status");
    }

    public boolean neverExpires() {
        return expiresAtEpochMillis <= 0;
    }
}
