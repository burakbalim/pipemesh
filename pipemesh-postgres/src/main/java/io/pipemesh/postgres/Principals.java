package io.pipemesh.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.capability.Principal;

import java.util.LinkedHashSet;
import java.util.Set;

/** Stores who an execution runs on behalf of, so a resume can ask the same question. */
final class Principals {

    private Principals() {
    }

    static JsonNode toJson(Principal principal) {
        ObjectNode json = JsonNodeFactory.instance.objectNode()
                .put("id", principal.id())
                .put("unrestricted", principal.unrestricted());

        var permissions = json.putArray("permissions");
        principal.permissions().stream().sorted().forEach(permissions::add);
        return json;
    }

    static Principal fromJson(JsonNode json) {
        String id = json.path("id").asText("");
        if (id.isBlank()) {
            // A row written before principals existed belongs to whoever was
            // running the process, which is the system itself.
            return Principal.SYSTEM;
        }
        Set<String> permissions = new LinkedHashSet<>();
        json.path("permissions").forEach(permission -> permissions.add(permission.asText()));

        return new Principal(id, permissions, json.path("unrestricted").asBoolean(false));
    }
}
