package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

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

    /**
     * Something happened elsewhere that an execution was waiting for (§9.7).
     *
     * <p>{@code signalId} is the wait's own id rather than the event's: the
     * publisher does not know which executions were listening, and the runtime
     * resolves that before resuming any of them.
     */
    record Event(String signalId, String name, JsonNode payload) implements ResumeSignal {

        public Event {
            Objects.requireNonNull(signalId, "signal id");
            Objects.requireNonNull(name, "event name");
            payload = payload == null ? NullNode.getInstance() : payload.deepCopy();
        }

        @Override
        public JsonNode payload() {
            return payload.deepCopy();
        }
    }

    /** A wait whose deadline passed before anything arrived. */
    record Expired(String signalId) implements ResumeSignal {

        public Expired {
            Objects.requireNonNull(signalId, "signal id");
        }
    }
}
