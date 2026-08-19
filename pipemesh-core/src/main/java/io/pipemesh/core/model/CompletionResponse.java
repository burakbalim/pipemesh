package io.pipemesh.core.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * A model's answer plus what it cost.
 *
 * <p>Token counts are part of the contract rather than a provider detail: they
 * feed {@code llm.input_tokens} / {@code llm.output_tokens} and, later, cost
 * tracking (§22).
 */
public record CompletionResponse(
        JsonNode content,
        String providerModel,
        long inputTokens,
        long outputTokens,
        long latencyMillis) {

    public CompletionResponse {
        Objects.requireNonNull(content, "content");
        content = content.deepCopy();
        providerModel = providerModel == null ? "" : providerModel;
    }

    @Override
    public JsonNode content() {
        return content.deepCopy();
    }
}
