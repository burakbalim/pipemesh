package io.pipemesh.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.modelcontextprotocol.spec.McpSchema;
import io.pipemesh.core.capability.CapabilityCall;
import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityProvider;
import io.pipemesh.core.capability.CapabilityResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Reaches a capability through an MCP tool.
 *
 * <p>One {@link CapabilityProvider} among others. A capability registered like
 * this:
 *
 * <pre>
 * "execution": { "type": "mcp", "server": "places", "tool": "search" }
 * </pre>
 *
 * <p>is invoked from a workflow that says only {@code "capability":
 * "venue_search"} and has no idea MCP exists. Swapping this capability to REST
 * later is a registration edit, not a workflow change (§9.8, §10).
 *
 * <p>Tool calls are provider I/O and happen outside any transaction.
 */
public final class McpCapabilityProvider implements CapabilityProvider {

    public static final String TYPE = "mcp";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_ARGUMENT = "input";

    private final Map<String, McpServerConnection> servers;

    public McpCapabilityProvider(List<McpServerConnection> servers) {
        this.servers = Objects.requireNonNull(servers, "servers").stream()
                .collect(Collectors.toUnmodifiableMap(McpServerConnection::name, server -> server));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public CapabilityResult invoke(
            CapabilityDescriptor capability, JsonNode input, CapabilityCall call) {
        JsonNode execution = capability.execution();
        String serverName = execution.path("server").asText("");
        String tool = execution.path("tool").asText("");

        McpServerConnection server = servers.get(serverName);
        if (server == null) {
            return new CapabilityResult.Failure("mcp.unknown_server",
                    "capability '" + capability.id() + "' names MCP server '" + serverName
                            + "', which is not connected", false);
        }
        if (tool.isBlank()) {
            return new CapabilityResult.Failure("mcp.missing_tool",
                    "capability '" + capability.id() + "' does not name a tool", false);
        }

        try {
            return resultOf(server.call(tool, argumentsFrom(execution, input)));
        } catch (RuntimeException failure) {
            // A tool call crosses a process boundary; treating that as retryable
            // is the honest default, and the retry policy decides what to do.
            return new CapabilityResult.Failure("mcp.call_failed",
                    failure.getClass().getSimpleName() + ": " + failure.getMessage(), true);
        }
    }

    /**
     * An object input is passed through as the tool's arguments; anything else is
     * wrapped under a single name, since MCP tools take named arguments and a
     * workflow may well hand over a bare string.
     */
    private Map<String, Object> argumentsFrom(JsonNode execution, JsonNode input) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return Map.of();
        }
        if (input.isObject()) {
            return JSON.convertValue(input, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(
                execution.path("argument").asText(DEFAULT_ARGUMENT),
                JSON.convertValue(input, Object.class));
        return arguments;
    }

    private CapabilityResult resultOf(McpSchema.CallToolResult result) {
        JsonNode content = contentOf(result);
        if (Boolean.TRUE.equals(result.isError())) {
            return new CapabilityResult.Failure("mcp.tool_error", content.asText(), false);
        }
        return new CapabilityResult.Success(content);
    }

    /**
     * MCP returns a list of content blocks; a tool that answers with JSON does it
     * as text. Text that parses becomes structured output, text that does not
     * stays text — the workflow should not have to care which the tool chose.
     */
    private JsonNode contentOf(McpSchema.CallToolResult result) {
        String text = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .collect(Collectors.joining());

        return asJson(text).orElseGet(() -> TextNode.valueOf(text));
    }

    private Optional<JsonNode> asJson(String text) {
        if (text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readTree(text));
        } catch (Exception notJson) {
            return Optional.empty();
        }
    }
}
