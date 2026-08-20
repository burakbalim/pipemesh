package io.pipemesh.core.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Resolves a schema id to its definition (§31).
 *
 * <p>A schema is a shared artifact — two workflows extracting the same shape
 * should agree on it, and changing it should be one edit rather than a search
 * through every workflow that inlined a copy.
 */
public interface SchemaRegistry {

    Optional<JsonNode> find(String schemaId);
}
