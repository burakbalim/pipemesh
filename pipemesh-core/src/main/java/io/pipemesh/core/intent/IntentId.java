package io.pipemesh.core.intent;

import java.util.Objects;

/** What a message was understood to be asking for. */
public record IntentId(String value) {

    public IntentId {
        Objects.requireNonNull(value, "intent id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("intent id must not be blank");
        }
    }

    public static IntentId of(String value) {
        return new IntentId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
