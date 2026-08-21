package io.pipemesh.grpc;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.pipemesh.core.capability.CapabilityCall;
import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityId;
import io.pipemesh.core.capability.CapabilityKind;
import io.pipemesh.core.capability.CapabilityProvider;
import io.pipemesh.core.capability.CapabilityResult;
import io.pipemesh.core.capability.InMemoryCapabilityRegistry;
import io.pipemesh.core.capability.Principal;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.WorkflowRuntime;
import io.pipemesh.core.execution.step.CapabilityStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.proto.v1.ExecutionStatus;
import io.pipemesh.proto.v1.PipeMeshGrpc;
import io.pipemesh.proto.v1.StartExecutionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A remote caller's permissions come from the server's own resolver, never from
 * anything the caller said (§23).
 */
class RemotePrincipalTest {

    private static final Metadata.Key<String> CALLER =
            Metadata.Key.of("x-caller", Metadata.ASCII_STRING_MARSHALLER);

    private static final String WORKFLOW = """
            {
              "id": "refund_flow", "version": "1.0", "entry": "refund",
              "steps": [
                {"id": "refund", "type": "capability", "capability": "refund_payment",
                 "output": "receipt", "next": "done"},
                {"id": "done", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    private record AlwaysSucceeds(String type) implements CapabilityProvider {

        @Override
        public CapabilityResult invoke(
                CapabilityDescriptor capability, com.fasterxml.jackson.databind.JsonNode input,
                CapabilityCall call) {
            return new CapabilityResult.Success(JsonNodeFactory.instance.objectNode().put("ok", true));
        }
    }

    /** Trusts a header, the way a real deployment would trust a validated token. */
    private static final PrincipalResolver BY_HEADER = metadata -> {
        String caller = metadata.get(CALLER);
        if (caller == null) {
            return Principal.ANONYMOUS;
        }
        return "manager".equals(caller)
                ? Principal.of("manager", "billing.refund")
                : Principal.of(caller);
    };

    private PipeMeshServer server;
    private ManagedChannel channel;

    @BeforeEach
    void startServer() throws IOException {
        InMemoryStateStore stateStore = new InMemoryStateStore();

        InMemoryCapabilityRegistry capabilities = new InMemoryCapabilityRegistry().register(
                new CapabilityDescriptor(
                        CapabilityId.of("refund_payment"), "", CapabilityKind.APPLICATION,
                        "billing-team", "1.0", List.of("billing.refund"), null, null,
                        JsonNodeFactory.instance.objectNode().put("type", "grpc")));

        StepExecutors executors = StepExecutors.of(
                new CapabilityStepExecutor(capabilities, List.of(new AlwaysSucceeds("grpc"))),
                new TerminalStepExecutor());

        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        workflows.register(new WorkflowDefinitionReader().read(WORKFLOW));

        ExecutionUpdateBroker broker = new ExecutionUpdateBroker();
        WorkflowRuntime runtime = new DefaultWorkflowRuntime(
                workflows, stateStore, new WorkflowExecutor(stateStore, executors, broker));

        server = new PipeMeshServer(runtime, broker, 0, null, null, BY_HEADER).start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.close();
    }

    private PipeMeshGrpc.PipeMeshBlockingStub callerNamed(String caller) {
        PipeMeshGrpc.PipeMeshBlockingStub stub = PipeMeshGrpc.newBlockingStub(channel);
        if (caller == null) {
            return stub;
        }
        Metadata headers = new Metadata();
        headers.put(CALLER, caller);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private io.pipemesh.proto.v1.ExecutionHandle refundAs(String caller) {
        return callerNamed(caller).startExecution(StartExecutionRequest.newBuilder()
                .setWorkflowId("refund_flow")
                .build());
    }

    @Test
    void letsThroughACallerTheResolverVouchesFor() {
        assertEquals(ExecutionStatus.EXECUTION_STATUS_COMPLETED, refundAs("manager").getStatus());
    }

    @Test
    void refusesACallerTheResolverGivesNothingTo() {
        assertEquals(ExecutionStatus.EXECUTION_STATUS_FAILED, refundAs("intern").getStatus());
    }

    @Test
    void treatsAnUnidentifiedCallerAsHoldingNothing() {
        assertEquals(ExecutionStatus.EXECUTION_STATUS_FAILED, refundAs(null).getStatus(),
                "failing closed is the only safe direction for a default nobody chose");
    }

    @Test
    void thereIsNowhereForACallerToClaimItsOwnPermissions() {
        var fields = StartExecutionRequest.getDescriptor().getFields().stream()
                .map(field -> field.getName())
                .toList();

        assertTrue(fields.stream().noneMatch(name -> name.contains("permission")),
                "a request that carries its own answer to 'what may I do' has not been authorised");
        assertTrue(fields.stream().noneMatch(name -> name.contains("principal")), fields.toString());
    }
}
