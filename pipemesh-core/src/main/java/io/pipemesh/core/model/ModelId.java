package io.pipemesh.core.model;

import java.util.Objects;

/**
 * The alias a workflow uses for a model — {@code fast}, {@code reasoning} — never
 * a vendor's model name. Which provider and which model that resolves to is
 * registry configuration (§12).
 */
public record ModelId(String value) {

    public ModelId {
        Objects.requireNonNull(value, "model id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("model id must not be blank");
        }
    }

    public static ModelId of(String value) {
        return new ModelId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
