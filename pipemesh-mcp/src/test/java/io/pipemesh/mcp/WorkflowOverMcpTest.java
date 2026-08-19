package io.pipemesh.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityId;
import io.pipemesh.core.capability.CapabilityKind;
import io.pipemesh.core.capability.InMemoryCapabilityRegistry;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.ExecutionHandle;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionSnapshot;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.WorkflowRuntime;
import io.pipemesh.core.execution.step.CapabilityStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The acceptance criterion, end to end: a workflow reaches an MCP tool without
 * the workflow definition containing the word "mcp".
 */
class WorkflowOverMcpTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String WORKFLOW = """
            {
              "id": "find_venue", "version": "1.0", "entry": "search",
              "steps": [
                {"id": "search", "type": "capability", "capability": "venue_search",
                 "input": "$.input", "output": "venues", "next": "done"},
                {"id": "done", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    private static McpServerConnection places;

    @BeforeAll
    static void connect() {
        places = McpServerConnection.overStdio(
                "places",
                System.getProperty("java.home") + "/bin/java",
                List.of("-cp", System.getProperty("java.class.path"), TestMcpServer.class.getName()),
                Duration.ofSeconds(20));
    }

    @AfterAll
    static void disconnect() {
        if (places != null) {
            places.close();
        }
    }

    private WorkflowRuntime runtime() {
        InMemoryCapabilityRegistry capabilities = new InMemoryCapabilityRegistry().register(
                new CapabilityDescriptor(
                        CapabilityId.of("venue_search"), "Find suitable venues",
                        CapabilityKind.EXTERNAL, "platform-team", "1.0",
                        List.of("places.read"), null, null,
                        JsonNodeFactory.instance.objectNode()
                                .put("type", "mcp").put("server", "places").put("tool", "search")));

        StepExecutors executors = StepExecutors.of(
                new CapabilityStepExecutor(capabilities, List.of(new McpCapabilityProvider(List.of(places)))),
                new TerminalStepExecutor());

        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        workflows.register(new WorkflowDefinitionReader().read(WORKFLOW));

        InMemoryStateStore stateStore = new InMemoryStateStore();
        return new DefaultWorkflowRuntime(
                workflows, stateStore, new WorkflowExecutor(stateStore, executors));
    }

    @Test
    void runsAWorkflowThatReachesAnMcpToolItNeverNames() throws Exception {
        assertFalse(WORKFLOW.contains("mcp"), "the workflow must not mention the transport");

        WorkflowRuntime runtime = runtime();
        ExecutionHandle finished = runtime.start(ExecutionRequest.of(
                WorkflowId.of("find_venue"),
                new ExecutionInput((ObjectNode) JSON.readTree("{\"location\":\"Antalya\"}"))));

        assertEquals(ExecutionStatus.COMPLETED, finished.status());

        ExecutionSnapshot snapshot = runtime.snapshot(finished.executionId()).orElseThrow();
        assertEquals("Kaleici Hall", snapshot.variables().path("venues").get(0).path("name").asText());
        assertEquals("Antalya", snapshot.variables().path("venues").get(0).path("city").asText());
    }
}
