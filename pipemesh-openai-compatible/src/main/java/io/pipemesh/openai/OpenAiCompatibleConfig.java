package io.pipemesh.openai;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Where to reach a model and what to call it there.
 *
 * <p>{@code model} is the vendor's own name — {@code gpt-4o-mini},
 * {@code llama3.2}, {@code claude-sonnet-4-5} behind a proxy. A workflow never
 * sees it; it says {@code "model": "fast"} and the registry maps that alias to
 * one of these (§12).
 *
 * <p>{@code apiKey} is optional because not every endpoint has one: a local
 * Ollama or vLLM takes none.
 */
public record OpenAiCompatibleConfig(
        String id,
        String baseUrl,
        String model,
        String apiKey,
        Duration timeout) {

    public OpenAiCompatibleConfig {
        Objects.requireNonNull(id, "provider id");
        Objects.requireNonNull(model, "model");
        baseUrl = trimTrailingSlash(Objects.requireNonNull(baseUrl, "base url"));
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
    }

    /** A local endpoint that needs no credentials. */
    public static OpenAiCompatibleConfig local(String id, String baseUrl, String model) {
        return new OpenAiCompatibleConfig(id, baseUrl, model, null, null);
    }

    public Optional<String> apiKeyIfAny() {
        return Optional.ofNullable(apiKey).filter(key -> !key.isBlank());
    }

    public String completionsUrl() {
        return baseUrl + "/chat/completions";
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
