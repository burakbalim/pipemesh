package io.pipemesh.core.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;

/**
 * A model invocation, expressed without any vendor's vocabulary (§13).
 *
 * <p>{@code outputSchema}, when present, asks the provider for output matching
 * that JSON schema. How it gets there — a structured-output mode, a tool call, a
 * retry loop — is the provider's problem, not the workflow's (§21).
 */
public record CompletionRequest(
        ModelId model,
        String prompt,
        String promptVersion,
        JsonNode outputSchema) {

    public CompletionRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(prompt, "prompt");
        promptVersion = promptVersion == null ? "" : promptVersion;
        outputSchema = outputSchema == null ? null : outputSchema.deepCopy();
    }

    public Optional<JsonNode> outputSchemaIfAny() {
        return Optional.ofNullable(outputSchema).map(JsonNode::deepCopy);
    }
}
