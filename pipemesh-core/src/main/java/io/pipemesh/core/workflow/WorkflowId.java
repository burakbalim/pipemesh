package io.pipemesh.core.workflow;

import java.util.Objects;

/**
 * Identifier of a workflow definition. A plain string on the wire (§26.1).
 */
public record WorkflowId(String value) {

    public WorkflowId {
        Objects.requireNonNull(value, "workflow id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("workflow id must not be blank");
        }
    }

    public static WorkflowId of(String value) {
        return new WorkflowId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
