package io.pipemesh.core.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.List;
import java.util.Objects;

/**
 * A capability registration (§10).
 *
 * <p>Everything that distinguishes one capability from another — who owns it,
 * what version is pinned, what it may touch, how it is reached — lives here.
 * The workflow sees only {@link #id()}.
 *
 * <p>{@code execution} is left as raw JSON: the provider that claims
 * {@code execution.type} is the only thing that should understand its shape.
 */
public record CapabilityDescriptor(
        CapabilityId id,
        String description,
        CapabilityKind kind,
        String owner,
        String version,
        List<String> permissions,
        JsonNode inputSchema,
        JsonNode outputSchema,
        JsonNode execution) {

    public CapabilityDescriptor {
        Objects.requireNonNull(id, "capability id");
        Objects.requireNonNull(kind, "capability kind");
        Objects.requireNonNull(execution, "execution");
        description = description == null ? "" : description;
        permissions = List.copyOf(permissions == null ? List.of() : permissions);
        inputSchema = inputSchema == null ? NullNode.getInstance() : inputSchema.deepCopy();
        outputSchema = outputSchema == null ? NullNode.getInstance() : outputSchema.deepCopy();
        execution = execution.deepCopy();
    }

    /** The provider type this capability is reached through, e.g. {@code mcp}, {@code grpc}. */
    public String executionType() {
        return execution.path("type").asText("");
    }
}
