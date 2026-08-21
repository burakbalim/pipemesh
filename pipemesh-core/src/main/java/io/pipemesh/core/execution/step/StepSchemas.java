package io.pipemesh.core.execution.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Parses the schema fragments the built-in step types declare about themselves. */
final class StepSchemas {

    private static final ObjectMapper JSON = new ObjectMapper();

    private StepSchemas() {
    }

    static JsonNode parse(String schema) {
        try {
            return JSON.readTree(schema);
        } catch (Exception malformed) {
            // A built-in schema that does not parse is a mistake in this codebase,
            // not something a caller can do anything about.
            throw new IllegalStateException("a built-in step schema is broken", malformed);
        }
    }
}
