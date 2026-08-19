package io.pipemesh.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.pipemesh.core.model.CompletionRequest;
import io.pipemesh.core.model.CompletionResponse;
import io.pipemesh.core.model.ModelId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tested against a real HTTP server rather than a mocked client: the thing worth
 * checking is the request that goes out on the wire and the answer parsed back,
 * and a mock would only confirm the code calls itself the way it was written.
 *
 * <p>The server is the JDK's own, so this needs no network, no credentials and
 * no container.
 */
class OpenAiCompatibleProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ANSWER = """
            {
              "model": "llama3.2",
              "choices": [{"message": {"role": "assistant", "content": "%s"}}],
              "usage": {"prompt_tokens": 31, "completion_tokens": 12}
            }
            """;

    private HttpServer server;
    private final List<JsonNode> requestsSeen = new ArrayList<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> replyContent = new AtomicReference<>("Antalya");
    private final AtomicInteger replyStatus = new AtomicInteger(200);

    @BeforeEach
    void startEndpoint() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", this::reply);
        server.start();
    }

    @AfterEach
    void stopEndpoint() {
        server.stop(0);
    }

    private void reply(HttpExchange exchange) throws IOException {
        requestsSeen.add(JSON.readTree(exchange.getRequestBody()));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));

        byte[] body = replyStatus.get() == 200
                ? ANSWER.formatted(replyContent.get()).getBytes(StandardCharsets.UTF_8)
                : "{\"error\":\"nope\"}".getBytes(StandardCharsets.UTF_8);

        exchange.sendResponseHeaders(replyStatus.get(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    private OpenAiCompatibleProvider provider(String apiKey) {
        return new OpenAiCompatibleProvider(new OpenAiCompatibleConfig(
                "local", baseUrl(), "llama3.2", apiKey, null));
    }

    private CompletionRequest request(JsonNode outputSchema) {
        return new CompletionRequest(ModelId.of("fast"), "Where is the meetup?", "v1", outputSchema);
    }

    private JsonNode schema() {
        return JSON.createObjectNode().put("type", "object");
    }

    @Test
    void sendsThePromptAsAUserMessage() {
        provider(null).complete(request(null));

        JsonNode sent = requestsSeen.get(0);
        assertEquals("llama3.2", sent.path("model").asText());
        assertEquals("user", sent.path("messages").get(0).path("role").asText());
        assertEquals("Where is the meetup?", sent.path("messages").get(0).path("content").asText());
    }

    @Test
    void reportsWhatTheCallCost() {
        CompletionResponse response = provider(null).complete(request(null));

        assertEquals(31, response.inputTokens());
        assertEquals(12, response.outputTokens());
        assertEquals("llama3.2", response.providerModel());
    }

    @Test
    void returnsPlainTextWhenNoSchemaWasAskedFor() {
        CompletionResponse response = provider(null).complete(request(null));

        assertEquals("Antalya", response.content().asText());
        assertFalse(requestsSeen.get(0).has("response_format"));
    }

    @Test
    void asksForSchemaConstrainedOutputWhenTheStepDeclaredOne() {
        replyContent.set("{\\\"city\\\":\\\"Antalya\\\"}");

        CompletionResponse response = provider(null).complete(request(schema()));

        JsonNode format = requestsSeen.get(0).path("response_format");
        assertEquals("json_schema", format.path("type").asText());
        assertTrue(format.path("json_schema").path("strict").asBoolean());
        assertEquals("Antalya", response.content().path("city").asText());
    }

    @Test
    void handsBackTheTextWhenAStructuredAnswerIsNotJson() {
        replyContent.set("I could not comply");

        CompletionResponse response = provider(null).complete(request(schema()));

        assertEquals("I could not comply", response.content().asText());
    }

    @Test
    void sendsNoCredentialsToAnEndpointThatNeedsNone() {
        provider(null).complete(request(null));

        assertEquals(null, authorization.get());
    }

    @Test
    void sendsABearerTokenWhenOneIsConfigured() {
        provider("sk-test-123").complete(request(null));

        assertEquals("Bearer sk-test-123", authorization.get());
    }

    @Test
    void failsLoudlyWhenTheEndpointRejectsTheCall() {
        replyStatus.set(429);

        ModelCallException failure =
                assertThrows(ModelCallException.class, () -> provider(null).complete(request(null)));

        assertTrue(failure.getMessage().contains("429"));
    }

    @Test
    void failsWhenTheEndpointCannotBeReached() {
        OpenAiCompatibleProvider unreachable = new OpenAiCompatibleProvider(
                OpenAiCompatibleConfig.local("dead", "http://localhost:1", "llama3.2"));

        assertThrows(ModelCallException.class, () -> unreachable.complete(request(null)));
    }

    @Test
    void toleratesABaseUrlWithATrailingSlash() {
        OpenAiCompatibleConfig config =
                OpenAiCompatibleConfig.local("local", baseUrl() + "/", "llama3.2");

        assertEquals(baseUrl() + "/chat/completions", config.completionsUrl());
    }
}
