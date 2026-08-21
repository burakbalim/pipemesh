package io.pipemesh.runtime;

import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.proto.v1.ExecutionHandle;
import io.pipemesh.proto.v1.PipeMeshGrpc;
import io.pipemesh.proto.v1.StartExecutionRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runtime as something you can actually run.
 *
 * <p>Assembled from the example configuration directory rather than by hand,
 * because "adding a workflow is dropping in a file" (§46) is only true if the
 * thing that ships reads files.
 */
class RuntimeAssemblyTest {

    private static PostgreSQLContainer<?> postgres;

    private RuntimeAssembly runtime;
    private ManagedChannel channel;

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
    }

    @AfterEach
    void stop() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (runtime != null) {
            runtime.close();
        }
    }

    private static Path examples() {
        return Path.of("..", "examples", "approval-flow").toAbsolutePath().normalize();
    }

    private RuntimeSettings settings(Map<String, String> overrides) {
        Map<String, String> environment = new java.util.HashMap<>(Map.of(
                RuntimeSettings.CONFIG, examples().toString(),
                RuntimeSettings.PORT, "0"));
        environment.putAll(overrides);
        return RuntimeSettings.from(environment::get);
    }

    private RuntimeSettings durableSettings(Map<String, String> overrides) {
        Map<String, String> environment = new java.util.HashMap<>(Map.of(
                RuntimeSettings.DB_URL, postgres.getJdbcUrl(),
                RuntimeSettings.DB_USER, postgres.getUsername(),
                RuntimeSettings.DB_PASSWORD, postgres.getPassword()));
        environment.putAll(overrides);
        return settings(environment);
    }

    private PipeMeshGrpc.PipeMeshBlockingStub clientFor(RuntimeAssembly assembly) {
        channel = ManagedChannelBuilder.forTarget("localhost:" + assembly.port())
                .usePlaintext().build();
        return PipeMeshGrpc.newBlockingStub(channel);
    }

    @Test
    void aConfigurationDirectoryIsEnoughToServe() throws Exception {
        runtime = RuntimeAssembly.of(settings(Map.of())).start();

        assertTrue(runtime.port() > 0, "it bound a port and is listening");
    }

    @Test
    void aWorkflowFromTheDirectoryRuns() throws Exception {
        runtime = RuntimeAssembly.of(settings(Map.of(RuntimeSettings.DISPATCH, "off"))).start();

        ExecutionHandle handle = clientFor(runtime).startExecution(
                StartExecutionRequest.newBuilder()
                        .setWorkflowId("venue_booking")
                        .setOrganizationId("acme")
                        .build());

        assertTrue(!handle.getExecutionId().isBlank(),
                "no code names this workflow; it came out of the directory");
    }

    @Test
    void withoutADatabaseItStillRuns() throws Exception {
        RuntimeSettings settings = settings(Map.of());

        assertTrue(!settings.durable(), "trying it out must not require a database");

        runtime = RuntimeAssembly.of(settings).start();
        assertTrue(runtime.port() > 0);
    }

    @Test
    void withADatabaseItMigratesItsOwnSchema() {
        RuntimeSettings settings = durableSettings(Map.of());

        RuntimeAssembly.migrate(settings);

        assertEquals(1, tableCount("workflow_execution"),
                "nobody else is going to do this for an on-premise install");
    }

    /**
     * Several replicas starting together is the ordinary case in the deployment
     * this exists for, and a transaction alone does not make it safe.
     */
    @Test
    void severalMigrationsAtOnceDoNotRaceEachOther() throws Exception {
        RuntimeSettings settings = durableSettings(Map.of());
        ExecutorService replicas = Executors.newFixedThreadPool(4);
        AtomicInteger failures = new AtomicInteger();

        for (int replica = 0; replica < 4; replica++) {
            replicas.execute(() -> {
                try {
                    RuntimeAssembly.migrate(settings);
                } catch (RuntimeException clash) {
                    failures.incrementAndGet();
                }
            });
        }
        replicas.shutdown();
        assertTrue(replicas.awaitTermination(60, TimeUnit.SECONDS));

        assertEquals(0, failures.get(), "the lock made the losers wait, not fail");
        assertEquals(1, tableCount("workflow_execution"));
    }

    @Test
    void aMissingConfigurationDirectoryIsRefusedWithAReason() {
        Exception refused = assertThrows(IllegalStateException.class,
                () -> RuntimeSettings.from(name -> null));

        assertTrue(refused.getMessage().contains(RuntimeSettings.CONFIG), refused.getMessage());
    }

    @Test
    void dispatchingIsOnUnlessTurnedOff() {
        assertTrue(settings(Map.of()).dispatching());
        assertTrue(!settings(Map.of(RuntimeSettings.DISPATCH, "off")).dispatching());
    }

    /**
     * Sharing recovery's minute would make a single node look dead for up to a
     * minute after every request, which is what the first smoke run looked like.
     */
    @Test
    void dispatchDoesNotWaitAsLongAsRecovery() {
        RuntimeSettings settings = settings(Map.of());

        assertEquals(Duration.ofSeconds(1), settings.dispatchInterval());
        assertTrue(settings.dispatchInterval().compareTo(settings.recoveryInterval()) < 0);
    }

    @Test
    void aDispatchedExecutionIsDrivenWithoutAnybodyAskingAgain() throws Exception {
        runtime = RuntimeAssembly.of(durableSettings(Map.of())).start();
        var client = clientFor(runtime);

        ExecutionHandle handle = client.startExecution(StartExecutionRequest.newBuilder()
                .setWorkflowId("venue_booking")
                .setOrganizationId("acme")
                .build());

        assertEquals(io.pipemesh.proto.v1.ExecutionStatus.EXECUTION_STATUS_CREATED,
                handle.getStatus(), "the caller is not made to wait");

        var settled = io.pipemesh.proto.v1.ExecutionStatus.EXECUTION_STATUS_CREATED;
        for (int attempt = 0; attempt < 40 && settled
                == io.pipemesh.proto.v1.ExecutionStatus.EXECUTION_STATUS_CREATED; attempt++) {
            TimeUnit.MILLISECONDS.sleep(250);
            settled = client.getExecution(io.pipemesh.proto.v1.GetExecutionRequest.newBuilder()
                    .setExecutionId(handle.getExecutionId()).build()).getStatus();
        }

        assertTrue(settled != io.pipemesh.proto.v1.ExecutionStatus.EXECUTION_STATUS_CREATED,
                "a dispatcher picked it up on its own");
    }

    @Test
    void theRecoveryIntervalCanBeChanged() {
        assertEquals(Duration.ofMinutes(1), settings(Map.of()).recoveryInterval());
        assertEquals(Duration.ofSeconds(15),
                settings(Map.of(RuntimeSettings.RECOVERY_INTERVAL, "15S")).recoveryInterval());
    }

    private int tableCount(String table) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());

        try (var connection = source.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM information_schema.tables WHERE table_name = ?")) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
