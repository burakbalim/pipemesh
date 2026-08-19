package io.pipemesh.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.postgresql.util.PGobject;

import java.sql.SQLException;

/** Moves JSON between a {@code jsonb} column and a {@link JsonNode}. */
final class JsonColumn {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonColumn() {
    }

    static PGobject toJsonb(JsonNode value) throws SQLException {
        PGobject column = new PGobject();
        column.setType("jsonb");
        column.setValue(value == null || value.isNull() ? "{}" : value.toString());
        return column;
    }

    static ObjectNode readObject(String json) {
        if (json == null || json.isBlank()) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            JsonNode parsed = MAPPER.readTree(json);
            return parsed.isObject() ? (ObjectNode) parsed : JsonNodeFactory.instance.objectNode();
        } catch (Exception malformed) {
            throw new StateStoreException("stored json is unreadable", malformed);
        }
    }
}
