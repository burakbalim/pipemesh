package io.pipemesh.core.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An execution picked up after the process running it died (§15, §38).
 *
 * <p>{@code repeated} is the part worth knowing. Recovery has two endings: the
 * step could be run again, or it may already have taken effect and the execution
 * stops for a person. Both are recoveries; only one of them continues, and a
 * dashboard that cannot tell them apart is counting two different problems as
 * one.
 */
public record RecoveryEvent(ExecutionEvent execution, boolean repeated, String reason) {

    public RecoveryEvent {
        Objects.requireNonNull(execution, "execution");
        reason = reason == null ? "" : reason;
    }

    /** A recovery that carried on. */
    public static RecoveryEvent resumed(ExecutionEvent execution) {
        return new RecoveryEvent(execution, true, "");
    }

    /** A recovery that stopped, because repeating the step was not safe. */
    public static RecoveryEvent abandoned(ExecutionEvent execution, String reason) {
        return new RecoveryEvent(execution, false, reason);
    }

    public Map<String, String> attributes() {
        Map<String, String> attributes = new LinkedHashMap<>(execution.attributes());
        attributes.put(TelemetryAttributes.RECOVERY_REPEATED, String.valueOf(repeated));
        return Map.copyOf(attributes);
    }
}
