package io.pipemesh.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.pipemesh.core.model.CompletionChunk;
import io.pipemesh.core.model.CompletionRequest;
import io.pipemesh.core.model.CompletionResponse;
import io.pipemesh.core.model.MessagingProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Speaks the OpenAI chat-completions protocol over plain HTTP.
 *
 * <p>Deliberately not built on a vendor SDK. What this runtime asks of a model
 * is one request and two token counts; an SDK would bring auth, retries,
 * streaming, typed models and a dependency tree that every embedder of PipeMesh
 * would inherit for a surface this narrow. The JDK's own HTTP client and Jackson
 * are enough, and they are already here.
 *
 * <p>Targeting the protocol rather than a vendor also means one implementation
 * covers a great deal of ground:
 *
 * <pre>
 * OpenAI · Ollama · vLLM · LiteLLM proxy · OpenRouter · Groq · Together
 * </pre>
 *
 * <p>A LiteLLM proxy in front of this reaches everything else, without PipeMesh
 * learning a second wire format (§13).
 */
public final class OpenAiCompatibleProvider implements MessagingProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OpenAiCompatibleConfig config;
    private final HttpClient http;

    public OpenAiCompatibleProvider(OpenAiCompatibleConfig config) {
        this(config, HttpClient.newHttpClient());
    }

    public OpenAiCompatibleProvider(OpenAiCompatibleConfig config, HttpClient http) {
        this.config = Objects.requireNonNull(config, "config");
        this.http = Objects.requireNonNull(http, "http client");
    }

    @Override
    public String id() {
        return config.id();
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        long startedAt = System.currentTimeMillis();
        HttpResponse<String> response = send(body(request));

        if (response.statusCode() / 100 != 2) {
            throw new ModelCallException(
                    "model endpoint answered " + response.statusCode() + ": " + response.body());
        }
        JsonNode answer = parse(response.body());
        return new CompletionResponse(
                content(answer, request),
                answer.path("model").asText(config.model()),
                answer.path("usage").path("prompt_tokens").asLong(0),
                answer.path("usage").path("completion_tokens").asLong(0),
                System.currentTimeMillis() - startedAt);
    }

    @Override
    public CompletionResponse stream(CompletionRequest request, Consumer<CompletionChunk> onChunk) {
        long startedAt = System.currentTimeMillis();

        ObjectNode body = body(request);
        body.put("stream", true);
        // Without this the usage block never arrives and token counts silently
        // read zero — a cost metric that is quietly wrong is worse than one that
        // is loudly missing.
        body.putObject("stream_options").put("include_usage", true);

        StringBuilder answer = new StringBuilder();
        long[] tokens = new long[2];
        int[] index = {0};

        HttpResponse<Stream<String>> response = sendForLines(body);
        if (response.statusCode() / 100 != 2) {
            throw new ModelCallException("model endpoint answered " + response.statusCode());
        }

        try (Stream<String> lines = response.body()) {
            lines.forEach(line -> consume(line, answer, tokens, index, onChunk));
        }

        return new CompletionResponse(
                contentOf(answer.toString(), request),
                config.model(),
                tokens[0],
                tokens[1],
                System.currentTimeMillis() - startedAt);
    }

    /**
     * Reads one server-sent line.
     *
     * <p>The wire carries blank lines as keep-alives, a {@code [DONE]} sentinel
     * that is not JSON, and — with {@code include_usage} — a final frame whose
     * choices are empty and whose usage is the whole point. Treating any of those
     * as an answer chunk is how a stream ends up with a stray token or no cost.
     */
    private void consume(
            String line, StringBuilder answer, long[] tokens, int[] index,
            Consumer<CompletionChunk> onChunk) {

        if (!line.startsWith("data:")) {
            return;
        }
        String payload = line.substring("data:".length()).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return;
        }

        JsonNode frame;
        try {
            frame = JSON.readTree(payload);
        } catch (Exception notJson) {
            return;
        }

        JsonNode usage = frame.path("usage");
        if (usage.isObject()) {
            tokens[0] = usage.path("prompt_tokens").asLong(tokens[0]);
            tokens[1] = usage.path("completion_tokens").asLong(tokens[1]);
        }

        String text = frame.path("choices").path(0).path("delta").path("content").asText("");
        if (!text.isEmpty()) {
            answer.append(text);
            onChunk.accept(new CompletionChunk(text, index[0]++));
        }
    }

    private HttpResponse<Stream<String>> sendForLines(ObjectNode body) {
        try {
            return http.send(requestFor(body).build(), HttpResponse.BodyHandlers.ofLines());
        } catch (IOException unreachable) {
            throw new ModelCallException("could not reach " + config.completionsUrl(), unreachable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("interrupted while streaming from the model", interrupted);
        }
    }

    private ObjectNode body(CompletionRequest request) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", config.model());

        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", request.prompt());

        request.outputSchemaIfAny().ifPresent(schema -> structuredOutput(body, schema));
        return body;
    }

    /**
     * Asks for output matching a schema. Endpoints that do not understand
     * {@code json_schema} generally still honour the {@code json_object} shape,
     * which is why the format is requested rather than the schema being enforced
     * here — validating the answer is the caller's business (§21).
     */
    private void structuredOutput(ObjectNode body, JsonNode schema) {
        ObjectNode format = body.putObject("response_format");
        format.put("type", "json_schema");
        ObjectNode jsonSchema = format.putObject("json_schema");
        jsonSchema.put("name", "output");
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", schema);
    }

    private HttpRequest.Builder requestFor(ObjectNode body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.completionsUrl()))
                .timeout(config.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));

        config.apiKeyIfAny().ifPresent(key -> builder.header("Authorization", "Bearer " + key));
        return builder;
    }

    private HttpResponse<String> send(ObjectNode body) {
        try {
            return http.send(requestFor(body).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException unreachable) {
            throw new ModelCallException("could not reach " + config.completionsUrl(), unreachable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("interrupted while calling the model", interrupted);
        }
    }

    /**
     * A structured request gets its content parsed; a plain one stays text.
     *
     * <p>Models sometimes wrap JSON in a fenced block even when asked not to, so
     * unparseable content is returned as text rather than failing the step —
     * losing the answer would be worse than handing back what arrived.
     */
    private JsonNode content(JsonNode answer, CompletionRequest request) {
        return contentOf(
                answer.path("choices").path(0).path("message").path("content").asText(""), request);
    }

    private JsonNode contentOf(String text, CompletionRequest request) {
        if (request.outputSchemaIfAny().isEmpty()) {
            return TextNode.valueOf(text);
        }
        return asJson(text).orElseGet(() -> TextNode.valueOf(text));
    }

    private Optional<JsonNode> asJson(String text) {
        try {
            return Optional.of(JSON.readTree(text));
        } catch (Exception notJson) {
            return Optional.empty();
        }
    }

    private JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception malformed) {
            throw new ModelCallException("model endpoint answered with unreadable JSON", malformed);
        }
    }
}
