package io.pipemesh.grpc;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.pipemesh.core.capability.CapabilityCall;
import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityId;
import io.pipemesh.core.capability.CapabilityKind;
import io.pipemesh.core.capability.CapabilityResult;
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.proto.v1.CapabilityInvocation;
import io.pipemesh.proto.v1.CapabilityWorkerGrpc;
import io.pipemesh.proto.v1.WorkerMessage;
import io.pipemesh.proto.v1.WorkerRegistration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The other half of the boundary: business code living in its own process, called
 * by the runtime as a capability.
 *
 * <p>The worker here connects over a real socket with the generated stub, the way
 * an SDK in any language does.
 */
class WorkerCapabilityTest {

    private io.grpc.Server server;
    private ManagedChannel channel;
    private final WorkerRegistry registry = new WorkerRegistry();

    @BeforeEach
    void startServer() throws IOException {
        server = io.grpc.ServerBuilder.forPort(0)
                .addService(new CapabilityWorkerService(registry))
                .build()
                .start();

        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort())
                .usePlaintext()
                .build();
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    /** A worker that answers with whatever the given function returns. */
    private final class TestWorker implements AutoCloseable {

        private final StreamObserver<WorkerMessage> outbound;
        private final CountDownLatch registered = new CountDownLatch(1);
        final CountDownLatch invoked = new CountDownLatch(1);

        TestWorker(String organization, List<String> capabilities,
                   Function<CapabilityInvocation, WorkerMessage> answer) {

            CapabilityWorkerGrpc.CapabilityWorkerStub stub = CapabilityWorkerGrpc.newStub(channel);

            this.outbound = stub.connect(new StreamObserver<>() {

                @Override
                public void onNext(CapabilityInvocation invocation) {
                    invoked.countDown();
                    WorkerMessage reply = answer.apply(invocation);
                    if (reply != null) {
                        synchronized (WorkerCapabilityTest.TestWorker.this) {
                            outbound.onNext(reply);
                        }
                    }
                }

                @Override
                public void onError(Throwable failure) {
                }

                @Override
                public void onCompleted() {
                }
            });

            outbound.onNext(WorkerMessage.newBuilder()
                    .setRegistration(WorkerRegistration.newBuilder()
                            .setOrganizationId(organization)
                            .addAllCapabilityIds(capabilities)
                            .build())
                    .build());
            registered.countDown();
        }

        @Override
        public void close() {
            outbound.onCompleted();
        }
    }

    private WorkerMessage success(CapabilityInvocation invocation, String field, Object value) {
        var output = JsonNodeFactory.instance.objectNode();
        if (value instanceof Number number) {
            output.put(field, number.doubleValue());
        } else {
            output.put(field, String.valueOf(value));
        }
        return WorkerMessage.newBuilder()
                .setResult(io.pipemesh.proto.v1.CapabilityResult.newBuilder()
                        .setInvocationId(invocation.getInvocationId())
                        .setOutput(JsonStructs.toStruct(output))
                        .build())
                .build();
    }

    private CapabilityDescriptor capability(String id) {
        return new CapabilityDescriptor(
                CapabilityId.of(id), "", CapabilityKind.APPLICATION, "billing-team", "1.0",
                List.of(), null, null,
                JsonNodeFactory.instance.objectNode().put("type", "worker").put("capability", id));
    }

    private CapabilityCall callFrom(String organization) {
        return new CapabilityCall(
                OrganizationId.of(organization), ExecutionId.of("exec-1"), StepId.of("charge"), "");
    }

    private CapabilityResult invoke(String organization, String capabilityId) {
        return new WorkerCapabilityProvider(registry, Duration.ofSeconds(5)).invoke(
                capability(capabilityId),
                JsonNodeFactory.instance.objectNode().put("tier", "gold"),
                callFrom(organization));
    }

    private void awaitWorker() throws InterruptedException {
        for (int attempt = 0; attempt < 50 && registry.size() == 0; attempt++) {
            Thread.sleep(20);
        }
    }

    @Test
    void callsBusinessCodeLivingInAnotherProcess() throws Exception {
        try (TestWorker worker = new TestWorker(
                "acme", List.of("calculate_discount"),
                invocation -> success(invocation, "rate", 0.2))) {

            awaitWorker();
            CapabilityResult result = invoke("acme", "calculate_discount");

            CapabilityResult.Success success = assertInstanceOf(CapabilityResult.Success.class, result);
            assertEquals(0.2, success.output().path("rate").asDouble(), 0.0001);
        }
    }

    @Test
    void passesTheStepsInputToTheWorker() throws Exception {
        StringBuilder seen = new StringBuilder();

        try (TestWorker worker = new TestWorker(
                "acme", List.of("calculate_discount"),
                invocation -> {
                    seen.append(JsonStructs.toJson(invocation.getInput()).path("tier").asText());
                    return success(invocation, "rate", 0.2);
                })) {

            awaitWorker();
            invoke("acme", "calculate_discount");

            assertEquals("gold", seen.toString());
        }
    }

    @Test
    void refusesWhenNoWorkerServesTheCapability() throws Exception {
        try (TestWorker worker = new TestWorker(
                "acme", List.of("something_else"), invocation -> null)) {

            awaitWorker();
            CapabilityResult result = invoke("acme", "calculate_discount");

            CapabilityResult.Failure failure =
                    assertInstanceOf(CapabilityResult.Failure.class, result);
            assertEquals("worker.none_connected", failure.code());
            assertTrue(failure.retryable(), "a worker restart should be worth waiting one retry for");
        }
    }

    @Test
    void neverSendsOneOrganizationsCallToAnothersWorker() throws Exception {
        try (TestWorker worker = new TestWorker(
                "acme", List.of("calculate_discount"),
                invocation -> success(invocation, "rate", 0.2))) {

            awaitWorker();
            CapabilityResult result = invoke("someone-else", "calculate_discount");

            CapabilityResult.Failure failure =
                    assertInstanceOf(CapabilityResult.Failure.class, result);
            assertEquals("worker.none_connected", failure.code());
        }
    }

    @Test
    void failsTheCallWhenTheWorkerDiesHoldingIt() throws Exception {
        TestWorker worker = new TestWorker(
                "acme", List.of("calculate_discount"),
                invocation -> {
                    // Takes the call and hangs up, the way a killed process does.
                    worker().close();
                    return null;
                });
        current = worker;

        awaitWorker();
        CapabilityResult result = invoke("acme", "calculate_discount");

        CapabilityResult.Failure failure = assertInstanceOf(CapabilityResult.Failure.class, result);
        assertEquals("worker.died", failure.code());
        assertFalse(failure.retryable(),
                "the worker took the call; nothing on this side knows whether it ran");
    }

    @Test
    void forgetsAWorkerThatHungUp() throws Exception {
        try (TestWorker worker = new TestWorker("acme", List.of("calculate_discount"),
                invocation -> null)) {
            awaitWorker();
            assertEquals(1, registry.size());
        }

        for (int attempt = 0; attempt < 50 && registry.size() > 0; attempt++) {
            Thread.sleep(20);
        }
        assertEquals(0, registry.size(), "a worker that hung up should not be sent more work");
    }

    private volatile TestWorker current;

    private TestWorker worker() {
        return current;
    }
}
