package io.pipemesh.core.capability;

import java.util.Objects;

/** The name a workflow uses to invoke a capability — and all it ever knows about it (§9.8). */
public record CapabilityId(String value) {

    public CapabilityId {
        Objects.requireNonNull(value, "capability id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("capability id must not be blank");
        }
    }

    public static CapabilityId of(String value) {
        return new CapabilityId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
