package io.pipemesh.core.schema;

import java.util.Objects;

/**
 * One reason a value did not match its schema.
 *
 * <p>{@code path} points at the offending field rather than the whole document.
 * "the answer did not match the schema" sends someone reading a stack trace on a
 * hunt; "$.request.price expected number, got string" does not.
 */
public record SchemaViolation(String path, String message) {

    public SchemaViolation {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
    }

    @Override
    public String toString() {
        return path + " " + message;
    }
}
