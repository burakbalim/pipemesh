package io.pipemesh.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityId;
import io.pipemesh.core.capability.CapabilityKind;
import io.pipemesh.core.capability.InMemoryCapabilityRegistry;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.ExecutionHandle;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.OrganizationId;
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
import io.pipemesh.mcp.McpCapabilityProvider;
import io.pipemesh.mcp.McpServerConnection;
import io.pipemesh.mcp.TestMcpServer;
import io.pipemesh.proto.v1.CapabilityInvocation;
import io.pipemesh.proto.v1.CapabilityWorkerGrpc;
import io.pipemesh.proto.v1.WorkerMessage;
import io.pipemesh.proto.v1.WorkerRegistration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim this whole design rests on, under test.
 *
 * <p>One workflow, two capabilities. One is an MCP tool in a child process; the
 * other is code in a worker that connected over gRPC. The two steps are the same
 * sentence, and the workflow contains neither "mcp" nor "worker" (§9.8).
 */
class MixedCapabilityWorkflowTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String WORKFLOW = """
            {
              "id": "plan_event", "version": "1.0", "entry": "find_venue",
              "steps": [
                {"id": "find_venue", "type": "capability", "capability": "venue_search",
                 "input": "$.input.location", "output": "venues", "next": "size_it"},

                {"id": "size_it", "type": "capability", "capability": "calculate_capacity",
                 "input": "$.input", "output": "capacity", "next": "done"},

                {"id": "done", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    private io.grpc.Server server;
    private ManagedChannel channel;
    private McpServerConnection places;
    private StreamObserver<WorkerMessage> worker;
    private final WorkerRegistry workers = new WorkerRegistry();
    private final InMemoryStateStore stateStore = new InMemoryStateStore();

    @BeforeEach
    void connectBothKinds() throws Exception {
        server = io.grpc.ServerBuilder.forPort(0)
                .addService(new CapabilityWorkerService(workers))
                .build()
                .start();

        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort())
                .usePlaintext()
                .build();

        places = McpServerConnection.overStdio(
                "places",
                System.getProperty("java.home") + "/bin/java",
                List.of("-cp", System.getProperty("java.class.path"), TestMcpServer.class.getName()),
                Duration.ofSeconds(20));

        worker = connectWorker();
        for (int attempt = 0; attempt < 50 && workers.size() == 0; attempt++) {
            Thread.sleep(20);
        }
    }

    @AfterEach
    void disconnect() throws InterruptedException {
        worker.onCompleted();
        places.close();
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    /** A worker that sizes a booking, the way somebody's own application would. */
    private StreamObserver<WorkerMessage> connectWorker() {
        CapabilityWorkerGrpc.CapabilityWorkerStub stub = CapabilityWorkerGrpc.newStub(channel);
        StreamObserver<WorkerMessage>[] outbound = new StreamObserver[1];

        outbound[0] = stub.connect(new StreamObserver<>() {

            @Override
            public void onNext(CapabilityInvocation invocation) {
                ObjectNode answer = JsonNodeFactory.instance.objectNode().put("seats", 6);
                synchronized (outbound) {
                    outbound[0].onNext(WorkerMessage.newBuilder()
                            .setResult(io.pipemesh.proto.v1.CapabilityResult.newBuilder()
                                    .setInvocationId(invocation.getInvocationId())
                                    .setOutput(JsonStructs.toStruct(answer))
                                    .build())
                            .build());
                }
            }

            @Override
            public void onError(Throwable failure) {
            }

            @Override
            public void onCompleted() {
            }
        });

        outbound[0].onNext(WorkerMessage.newBuilder()
                .setRegistration(WorkerRegistration.newBuilder()
                        .setOrganizationId("acme")
                        .addCapabilityIds("calculate_capacity")
                        .build())
                .build());
        return outbound[0];
    }

    private InMemoryCapabilityRegistry capabilities() {
        return new InMemoryCapabilityRegistry()
                .register(new CapabilityDescriptor(
                        CapabilityId.of("venue_search"), "Find venues", CapabilityKind.EXTERNAL,
                        "platform-team", "1.0", List.of(), null, null,
                        JsonNodeFactory.instance.objectNode()
                                .put("type", "mcp").put("server", "places")
                                .put("tool", "search").put("argument", "location")))
                .register(new CapabilityDescriptor(
                        CapabilityId.of("calculate_capacity"), "Size the booking",
                        CapabilityKind.APPLICATION, "events-team", "1.0", List.of(), null, null,
                        JsonNodeFactory.instance.objectNode()
                                .put("type", "worker").put("capability", "calculate_capacity")));
    }

    private WorkflowRuntime runtime() {
        StepExecutors executors = StepExecutors.of(
                new CapabilityStepExecutor(capabilities(), List.of(
                        new McpCapabilityProvider(List.of(places)),
                        new WorkerCapabilityProvider(workers, Duration.ofSeconds(10)))),
                new TerminalStepExecutor());

        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        workflows.register(new WorkflowDefinitionReader().read(WORKFLOW));

        return new DefaultWorkflowRuntime(
                workflows, stateStore, new WorkflowExecutor(stateStore, executors));
    }

    @Test
    void oneWorkflowReachesAnMcpToolAndSomebodysOwnCodeWithoutTellingThemApart() throws Exception {
        assertFalse(WORKFLOW.contains("mcp"), "the workflow must not name a transport");
        assertFalse(WORKFLOW.contains("worker"), "nor the other one");

        WorkflowRuntime runtime = runtime();
        ExecutionHandle finished = runtime.start(new ExecutionRequest(
                WorkflowId.of("plan_event"),
                new ExecutionInput((ObjectNode) JSON.readTree("{\"location\":\"Antalya\"}")),
                OrganizationId.of("acme"), null));

        assertEquals(ExecutionStatus.COMPLETED, finished.status());

        var variables = runtime.snapshot(finished.executionId()).orElseThrow().variables();
        assertEquals("Kaleici Hall", variables.path("venues").get(0).path("name").asText(),
                "this one came from an MCP server in another process");
        assertEquals(6, variables.path("capacity").path("seats").asInt(),
                "and this one from a worker that dialled in");
    }

    @Test
    void theTwoStepsAreWrittenTheSameWay() {
        long capabilitySteps = WORKFLOW.lines()
                .filter(line -> line.contains("\"type\": \"capability\""))
                .count();

        assertEquals(2, capabilitySteps);
        assertTrue(WORKFLOW.contains("\"capability\": \"venue_search\""));
        assertTrue(WORKFLOW.contains("\"capability\": \"calculate_capacity\""));
    }
}
