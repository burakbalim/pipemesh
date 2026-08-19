package io.pipemesh.core.workflow;

import java.util.Objects;

/**
 * Version of a workflow definition. Recorded on every execution so a run stays
 * reproducible after the definition changes (§24).
 */
public record WorkflowVersion(String value) {

    public WorkflowVersion {
        Objects.requireNonNull(value, "workflow version");
        if (value.isBlank()) {
            throw new IllegalArgumentException("workflow version must not be blank");
        }
    }

    public static WorkflowVersion of(String value) {
        return new WorkflowVersion(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
