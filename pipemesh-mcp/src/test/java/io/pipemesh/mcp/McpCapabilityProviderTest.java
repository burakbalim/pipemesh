package io.pipemesh.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityId;
import io.pipemesh.core.capability.CapabilityKind;
import io.pipemesh.core.capability.CapabilityResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against a real MCP server in a real child process, over real pipes.
 *
 * <p>The server is {@link TestMcpServer}, launched with this test's own
 * classpath, so the whole JSON-RPC handshake and tool call happen for real
 * without the test depending on npm, a network or an installed Node.
 */
class McpCapabilityProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static McpServerConnection places;
    private static McpCapabilityProvider provider;

    @BeforeAll
    static void connect() {
        places = McpServerConnection.overStdio(
                "places",
                System.getProperty("java.home") + "/bin/java",
                List.of("-cp", System.getProperty("java.class.path"),
                        TestMcpServer.class.getName()),
                Duration.ofSeconds(20));

        provider = new McpCapabilityProvider(List.of(places));
    }

    @AfterAll
    static void disconnect() {
        if (places != null) {
            places.close();
        }
    }

    private CapabilityDescriptor capability(String tool, ObjectNode extraExecution) {
        ObjectNode execution = JsonNodeFactory.instance.objectNode()
                .put("type", "mcp")
                .put("server", "places")
                .put("tool", tool);
        if (extraExecution != null) {
            execution.setAll(extraExecution);
        }
        return new CapabilityDescriptor(
                CapabilityId.of("venue_search"), "Find venues", CapabilityKind.EXTERNAL,
                "platform-team", "1.0", List.of("places.read"), null, null, execution);
    }

    private CapabilityDescriptor onServer(String server) {
        return new CapabilityDescriptor(
                CapabilityId.of("venue_search"), "", CapabilityKind.EXTERNAL, "", "1.0",
                List.of(), null, null,
                JsonNodeFactory.instance.objectNode()
                        .put("type", "mcp").put("server", server).put("tool", "search"));
    }

    @Test
    void completesTheHandshakeAndSeesTheServersTools() {
        assertTrue(places.toolNames().containsAll(List.of("search", "echo", "explode")));
    }

    @Test
    void callsAToolWithAnObjectAsItsArguments() throws Exception {
        JsonNode input = JSON.readTree("{\"location\":\"Antalya\"}");

        CapabilityResult result = provider.invoke(capability("search", null), input);

        CapabilityResult.Success success = assertInstanceOf(CapabilityResult.Success.class, result);
        assertEquals("Kaleici Hall", success.output().get(0).path("name").asText());
        assertEquals("Antalya", success.output().get(0).path("city").asText());
    }

    @Test
    void wrapsAScalarInputUnderTheDeclaredArgumentName() {
        CapabilityDescriptor capability = capability("search",
                JsonNodeFactory.instance.objectNode().put("argument", "location"));

        CapabilityResult result = provider.invoke(capability, TextNode.valueOf("Izmir"));

        CapabilityResult.Success success = assertInstanceOf(CapabilityResult.Success.class, result);
        assertEquals("Izmir", success.output().get(0).path("city").asText());
    }

    @Test
    void keepsToolOutputAsTextWhenItIsNotJson() {
        CapabilityDescriptor capability = capability("echo",
                JsonNodeFactory.instance.objectNode().put("argument", "input"));

        CapabilityResult result = provider.invoke(capability, TextNode.valueOf("just words"));

        CapabilityResult.Success success = assertInstanceOf(CapabilityResult.Success.class, result);
        assertEquals("just words", success.output().asText());
    }

    @Test
    void reportsAToolThatAnsweredWithAnError() {
        CapabilityResult result = provider.invoke(
                capability("explode", null), JSON.createObjectNode().put("input", "x"));

        CapabilityResult.Failure failure = assertInstanceOf(CapabilityResult.Failure.class, result);
        assertEquals("mcp.tool_error", failure.code());
        assertTrue(failure.message().contains("always fails"));
    }

    @Test
    void reportsACapabilityPointingAtAServerThatIsNotConnected() {
        CapabilityResult result = provider.invoke(
                onServer("nowhere"), JSON.createObjectNode());

        CapabilityResult.Failure failure = assertInstanceOf(CapabilityResult.Failure.class, result);
        assertEquals("mcp.unknown_server", failure.code());
    }

    @Test
    void treatsATransportFailureAsRetryable() {
        CapabilityDescriptor missingTool = capability("no_such_tool", null);

        CapabilityResult result = provider.invoke(
                missingTool, JSON.createObjectNode().put("location", "Antalya"));

        CapabilityResult.Failure failure = assertInstanceOf(CapabilityResult.Failure.class, result);
        assertTrue(failure.code().startsWith("mcp."), failure.code());
    }

    @Test
    void claimsTheExecutionTypeCapabilityRegistrationsUse() {
        assertEquals("mcp", provider.type());
    }
}
