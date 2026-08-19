package io.pipemesh.core.prompt;

import java.util.Objects;

/**
 * A prompt reference, version included: {@code venue_booking.extraction.v1}.
 *
 * <p>The version is part of the identity rather than a lookup parameter, so a
 * workflow pins the exact text it was written against and a new version is a new
 * artifact rather than an edit under a running workflow (§11, §24).
 */
public record PromptId(String value) {

    public PromptId {
        Objects.requireNonNull(value, "prompt id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("prompt id must not be blank");
        }
    }

    public static PromptId of(String value) {
        return new PromptId(value);
    }

    /** The trailing version segment, or empty when the id carries none. */
    public String version() {
        int lastDot = value.lastIndexOf('.');
        return lastDot < 0 ? "" : value.substring(lastDot + 1);
    }

    @Override
    public String toString() {
        return value;
    }
}
