package io.pipemesh.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/**
 * A minimal MCP server, launched as a child process by the tests.
 *
 * <p>Written rather than pulled from npm on purpose: the test then depends on no
 * registry, no network and no Node install, and still exercises the real
 * protocol over real pipes.
 *
 * <p>Anything it writes to stdout that is not a protocol message would corrupt
 * the stream, which is why it logs nothing.
 */
public final class TestMcpServer {

    private static final String SEARCH_SCHEMA = """
            {
              "type": "object",
              "properties": {"location": {"type": "string"}},
              "required": ["location"]
            }
            """;

    private static final String ECHO_SCHEMA = """
            {"type": "object", "properties": {"input": {"type": "string"}}}
            """;

    public static void main(String[] args) throws Exception {
        McpServer.sync(new StdioServerTransportProvider(McpJsonMapper.getDefault()))
                .serverInfo("pipemesh-test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tool(
                        tool("search", "Find venues in a city", SEARCH_SCHEMA),
                        (exchange, arguments) -> venuesIn(String.valueOf(arguments.get("location"))))
                .tool(
                        tool("echo", "Echo the argument back", ECHO_SCHEMA),
                        (exchange, arguments) -> text(String.valueOf(arguments.get("input"))))
                .tool(
                        tool("explode", "Always fails", ECHO_SCHEMA),
                        (exchange, arguments) -> error("this tool always fails"))
                .build();

        Thread.currentThread().join();
    }

    private static McpSchema.Tool tool(String name, String description, String inputSchema) {
        return McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(McpJsonMapper.getDefault(), inputSchema)
                .build();
    }

    private static McpSchema.CallToolResult venuesIn(String location) {
        return text("[{\"name\":\"Kaleici Hall\",\"city\":\"" + location + "\"}]");
    }

    private static McpSchema.CallToolResult text(String body) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(body)), false);
    }

    private static McpSchema.CallToolResult error(String message) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(message)), true);
    }

    private TestMcpServer() {
    }
}
