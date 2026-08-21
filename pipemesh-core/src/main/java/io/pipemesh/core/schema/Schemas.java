package io.pipemesh.core.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Reads the schemas that ship with the runtime, and merges shapes. */
public final class Schemas {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOCATION = "/io/pipemesh/core/schema/";

    private Schemas() {
    }

    public static JsonNode load(String name) {
        try (InputStream schema = Schemas.class.getResourceAsStream(LOCATION + name)) {
            if (schema == null) {
                throw new IllegalStateException("a schema that ships with the runtime is missing: " + name);
            }
            return JSON.readTree(schema);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * Combines the fields every step may carry with the ones a step type declares.
     *
     * <p>The result is closed: a field neither side named is refused. That is what
     * lets an open set of step types coexist with a format that admits no
     * surprises — each type closes its own door, and core holds the frame
     * (§23.1, §46).
     */
    public static JsonNode merge(JsonNode common, JsonNode declared) {
        ObjectNode merged = common.deepCopy();
        ObjectNode properties = merged.withObject("/properties");

        declared.path("properties").fields().forEachRemaining(
                property -> properties.set(property.getKey(), property.getValue()));

        declared.path("required").forEach(required -> merged.withArray("/required").add(required));

        merged.put("additionalProperties", false);
        merged.put("type", "object");
        return merged;
    }
}
