package io.pipemesh.runtime;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.pipemesh.proto.v1.ExecutionHandle;
import io.pipemesh.proto.v1.ExecutionUpdate;
import io.pipemesh.proto.v1.PipeMeshGrpc;
import io.pipemesh.proto.v1.StartExecutionRequest;
import io.pipemesh.proto.v1.WatchExecutionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two processes, one database: an execution driven by one, watched through the
 * other (§30.1).
 *
 * <p>This is the failure the cloud composition exists to close. Before it, a
 * watch on the wrong replica said nothing at all — no error, just silence, which
 * §30.1 spells out is indistinguishable from a workflow that is thinking.
 */
class CrossProcessWatchTest {

    private static PostgreSQLContainer<?> postgres;

    private final List<RuntimeAssembly> running = new ArrayList<>();
    private final List<ManagedChannel> channels = new ArrayList<>();

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
    }

    @AfterEach
    void stop() {
        channels.forEach(ManagedChannel::shutdownNow);
        running.forEach(RuntimeAssembly::close);
        running.clear();
        channels.clear();
    }

    /** One process of the cloud composition: same image, one variable apart. */
    private RuntimeAssembly instance(boolean dispatching) throws Exception {
        Map<String, String> environment = new HashMap<>(Map.of(
                RuntimeSettings.CONFIG,
                Path.of("..", "sdk", "testdata").toAbsolutePath().normalize().toString(),
                RuntimeSettings.PORT, "0",
                RuntimeSettings.DB_URL, postgres.getJdbcUrl(),
                RuntimeSettings.DB_USER, postgres.getUsername(),
                RuntimeSettings.DB_PASSWORD, postgres.getPassword(),
                RuntimeSettings.DISPATCH, dispatching ? "on" : "off",
                RuntimeSettings.DISPATCH_INTERVAL, "2S",
                // An API replica neither dispatches nor drives: other processes do.
                RuntimeSettings.START, "dispatched"));

        RuntimeAssembly assembly = RuntimeAssembly.of(
                RuntimeSettings.from(environment::get)).start();
        running.add(assembly);
        return assembly;
    }

    private static List<String> untilSuspended(Iterator<ExecutionUpdate> updates) {
        List<String> kinds = new ArrayList<>();
        for (int read = 0; read < 20; read++) {
            String kind = updates.next().getUpdateCase().name();
            kinds.add(kind);
            if ("SUSPENDED".equals(kind)) {
                return kinds;
            }
        }
        throw new AssertionError("the execution never suspended: " + kinds);
    }

    /** Over a hundred, so the workflow stops for an approval rather than finishing. */
    private static com.google.protobuf.Struct costing(int price) {
        return com.google.protobuf.Struct.newBuilder()
                .putFields("price", com.google.protobuf.Value.newBuilder()
                        .setNumberValue(price).build())
                .build();
    }

    private PipeMeshGrpc.PipeMeshBlockingStub clientFor(RuntimeAssembly assembly) {
        ManagedChannel channel = ManagedChannelBuilder
                .forTarget("localhost:" + assembly.port()).usePlaintext().build();
        channels.add(channel);
        return PipeMeshGrpc.newBlockingStub(channel);
    }

    @Test
    void anExecutionDrivenElsewhereIsStillWatchable() throws Exception {
        RuntimeAssembly api = instance(false);
        RuntimeAssembly driver = instance(true);
        assertTrue(driver.port() != api.port(), "two processes, two ports");

        // Started through the API replica, which will not drive it.
        ExecutionHandle handle = clientFor(api).startExecution(StartExecutionRequest.newBuilder()
                .setWorkflowId("venue_booking")
                .setOrganizationId("acme")
                .setInput(costing(250))
                .build());

        Iterator<ExecutionUpdate> updates = clientFor(api).watchExecution(
                WatchExecutionRequest.newBuilder()
                        .setExecutionId(handle.getExecutionId()).build());

        // Read until it stops for the approval. The stream stays open there —
        // a waiting execution has not ended — so draining to the end would wait
        // for a person who is never coming.
        List<String> kinds = untilSuspended(updates);

        assertEquals("STARTED", kinds.get(0), "the arrival snapshot");
        assertTrue(kinds.contains("STEP_STARTED"),
                "a step began on the other process and this one heard about it: " + kinds);
        assertEquals("SUSPENDED", kinds.get(kinds.size() - 1),
                "the workflow stops for an approval, and the stream says so");
    }

    @Test
    void aWatcherIsNumberedByWhicheverProcessServesIt() throws Exception {
        RuntimeAssembly api = instance(false);
        instance(true);

        ExecutionHandle handle = clientFor(api).startExecution(StartExecutionRequest.newBuilder()
                .setWorkflowId("venue_booking")
                .setOrganizationId("acme")
                .build());

        Iterator<ExecutionUpdate> updates = clientFor(api).watchExecution(
                WatchExecutionRequest.newBuilder()
                        .setExecutionId(handle.getExecutionId()).build());

        List<Long> sequences = new ArrayList<>();
        sequences.add(updates.next().getSequence());
        while (updates.hasNext()) {
            sequences.add(updates.next().getSequence());
        }

        assertEquals(0L, sequences.get(0), "the arrival snapshot is always zero");
        for (int index = 1; index < sequences.size(); index++) {
            assertEquals(index, sequences.get(index),
                    "numbered by the process serving this stream, with nothing missing: "
                            + sequences);
        }
    }

    /** Its own notification comes back; delivering it again would double every stream. */
    @Test
    void aProcessDoesNotServeItsOwnNotificationTwice() throws Exception {
        RuntimeAssembly single = instance(true);

        ExecutionHandle handle = clientFor(single).startExecution(
                StartExecutionRequest.newBuilder()
                        .setWorkflowId("policy_check")
                        .setOrganizationId("acme")
                        .build());

        Iterator<ExecutionUpdate> updates = clientFor(single).watchExecution(
                WatchExecutionRequest.newBuilder()
                        .setExecutionId(handle.getExecutionId()).build());

        List<String> kinds = new ArrayList<>();
        kinds.add(updates.next().getUpdateCase().name());
        while (updates.hasNext()) {
            kinds.add(updates.next().getUpdateCase().name());
        }

        assertEquals(1, kinds.stream().filter("FINISHED"::equals).count(),
                "one execution, one ending: " + kinds);
    }
}
