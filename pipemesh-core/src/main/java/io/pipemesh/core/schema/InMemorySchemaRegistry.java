package io.pipemesh.core.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Holds schemas in memory, keyed by the file name they came from. */
public final class InMemorySchemaRegistry implements SchemaRegistry {

    private final Map<String, JsonNode> schemas = new ConcurrentHashMap<>();

    public InMemorySchemaRegistry register(String schemaId, JsonNode schema) {
        schemas.put(
                Objects.requireNonNull(schemaId, "schema id"),
                Objects.requireNonNull(schema, "schema").deepCopy());
        return this;
    }

    @Override
    public Optional<JsonNode> find(String schemaId) {
        return Optional.ofNullable(schemas.get(schemaId)).map(JsonNode::deepCopy);
    }
}
