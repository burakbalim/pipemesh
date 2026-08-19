package io.pipemesh.core.workflow;

import java.util.Objects;

/** Identifier of a step within one workflow definition. */
public record StepId(String value) {

    public StepId {
        Objects.requireNonNull(value, "step id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("step id must not be blank");
        }
    }

    public static StepId of(String value) {
        return new StepId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
