package io.pipemesh.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.pipemesh.core.model.CompletionChunk;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleStreamingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What a real endpoint sends: deltas, keep-alives, a usage frame, then [DONE]. */
    private static final String SSE = """
            data: {"choices":[{"delta":{"content":"Ant"}}]}

            data: {"choices":[{"delta":{"content":"alya"}}]}

            : keep-alive

            data: {"choices":[{"delta":{}}]}

            data: {"choices":[],"usage":{"prompt_tokens":18,"completion_tokens":4}}

            data: [DONE]

            """;

    private HttpServer server;
    private final AtomicReference<JsonNode> requestSeen = new AtomicReference<>();

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
        requestSeen.set(JSON.readTree(exchange.getRequestBody()));
        byte[] body = SSE.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private OpenAiCompatibleProvider provider() {
        return new OpenAiCompatibleProvider(OpenAiCompatibleConfig.local(
                "local", "http://localhost:" + server.getAddress().getPort() + "/v1", "llama3.2"));
    }

    private CompletionRequest request() {
        return new CompletionRequest(ModelId.of("fast"), "Where is it?", "v1", null);
    }

    @Test
    void handsOverEachPieceAsItArrives() {
        List<CompletionChunk> chunks = new ArrayList<>();

        provider().stream(request(), chunks::add);

        assertEquals(List.of("Ant", "alya"), chunks.stream().map(CompletionChunk::text).toList());
        assertEquals(List.of(0, 1), chunks.stream().map(CompletionChunk::index).toList());
    }

    @Test
    void assemblesThePiecesIntoOneAnswer() {
        CompletionResponse response = provider().stream(request(), chunk -> {
        });

        assertEquals("Antalya", response.content().asText());
    }

    @Test
    void keepsReportingWhatTheCallCost() {
        CompletionResponse response = provider().stream(request(), chunk -> {
        });

        assertEquals(18, response.inputTokens());
        assertEquals(4, response.outputTokens());
    }

    @Test
    void asksTheEndpointToIncludeUsage() {
        provider().stream(request(), chunk -> {
        });

        assertTrue(requestSeen.get().path("stream").asBoolean());
        assertTrue(requestSeen.get().path("stream_options").path("include_usage").asBoolean(),
                "without this the token counts silently read zero");
    }

    @Test
    void ignoresKeepAlivesEmptyDeltasAndTheDoneSentinel() {
        List<CompletionChunk> chunks = new ArrayList<>();

        provider().stream(request(), chunks::add);

        assertEquals(2, chunks.size(), "only the two real deltas are answer content");
    }

    @Test
    void aProviderThatCannotStreamStillWorks() {
        List<CompletionChunk> chunks = new ArrayList<>();

        CompletionResponse response = new NonStreamingProvider().stream(request(), chunks::add);

        assertEquals("all at once", response.content().asText());
        assertEquals(1, chunks.size(), "one large piece instead of many small ones");
    }

    /** Implements only complete(), which is all the interface requires. */
    private static final class NonStreamingProvider implements io.pipemesh.core.model.MessagingProvider {

        @Override
        public String id() {
            return "plain";
        }

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            return new CompletionResponse(JSON.getNodeFactory().textNode("all at once"), "plain", 3, 2, 1);
        }
    }
}
