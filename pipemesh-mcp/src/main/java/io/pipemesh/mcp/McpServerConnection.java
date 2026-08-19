package io.pipemesh.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A connected MCP server, named the way capability registrations refer to it.
 *
 * <p>Connections are long-lived and shared: an MCP server over stdio is a child
 * process, and starting one per capability invocation would make every tool call
 * pay for a process launch.
 */
public final class McpServerConnection implements AutoCloseable {

    private final String name;
    private final McpSyncClient client;

    private McpServerConnection(String name, McpSyncClient client) {
        this.name = name;
        this.client = client;
    }

    /**
     * Launches an MCP server as a child process and completes the handshake.
     *
     * @param name    how capability registrations refer to this server
     * @param command the executable, e.g. {@code npx} or {@code java}
     */
    public static McpServerConnection overStdio(
            String name, String command, List<String> arguments, Duration timeout) {

        Objects.requireNonNull(name, "server name");
        ServerParameters parameters = ServerParameters.builder(command)
                .args(arguments == null ? List.of() : arguments)
                .build();

        McpSyncClient client = McpClient
                .sync(new StdioClientTransport(parameters, McpJsonMapper.getDefault()))
                .requestTimeout(timeout == null ? Duration.ofSeconds(30) : timeout)
                .build();

        client.initialize();
        return new McpServerConnection(name, client);
    }

    public String name() {
        return name;
    }

    public List<String> toolNames() {
        return client.listTools().tools().stream()
                .map(io.modelcontextprotocol.spec.McpSchema.Tool::name)
                .toList();
    }

    io.modelcontextprotocol.spec.McpSchema.CallToolResult call(
            String tool, Map<String, Object> arguments) {

        return client.callTool(
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(tool, arguments));
    }

    @Override
    public void close() {
        client.closeGracefully();
    }
}
