package io.pipemesh.core.execution;

import java.util.Objects;

/**
 * An external decision that lets a suspended execution continue.
 *
 * <p>{@code signalId} is what makes resume idempotent: delivering the same
 * decision twice must not advance the execution twice.
 */
public sealed interface ResumeSignal {

    String signalId();

    record Approval(String signalId, boolean approved, String decidedBy, String comment)
            implements ResumeSignal {

        public Approval {
            Objects.requireNonNull(signalId, "signal id");
            if (signalId.isBlank()) {
                throw new IllegalArgumentException("signal id must not be blank");
            }
            comment = comment == null ? "" : comment;
        }
    }
}
