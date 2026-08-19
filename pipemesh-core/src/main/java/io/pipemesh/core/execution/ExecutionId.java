package io.pipemesh.core.execution;

import java.util.Objects;
import java.util.UUID;

/** Identifier of a single workflow execution. A plain string on the wire (§26.1). */
public record ExecutionId(String value) {

    public ExecutionId {
        Objects.requireNonNull(value, "execution id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("execution id must not be blank");
        }
    }

    public static ExecutionId of(String value) {
        return new ExecutionId(value);
    }

    public static ExecutionId generate() {
        return new ExecutionId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
