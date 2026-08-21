package io.pipemesh.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.pipemesh.core.execution.RecoveryScheduler;
import io.pipemesh.core.execution.WorkflowRuntime;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Serves a runtime over gRPC (§26.3, remote mode).
 *
 * <p>It takes an assembled runtime rather than assembling one. Which state store,
 * which providers, which workflows — those are the application's decisions, and a
 * server that made them would be a second, quieter configuration system.
 */
public final class PipeMeshServer implements AutoCloseable {

    private final Server server;
    private final RecoveryScheduler recovery;

    public PipeMeshServer(WorkflowRuntime runtime, ExecutionUpdateBroker broker, int port) {
        this(runtime, broker, port, null);
    }

    /**
     * @param recovery started and stopped with the server, or {@code null} when
     *                 the application runs recovery itself. Serving a runtime
     *                 without anything sweeping means executions left behind by a
     *                 crashed process — or by a worker that died mid-call — stay
     *                 stuck, and the durability this runtime advertises quietly
     *                 depends on someone remembering.
     */
    public PipeMeshServer(
            WorkflowRuntime runtime, ExecutionUpdateBroker broker, int port, RecoveryScheduler recovery) {

        this(runtime, broker, port, recovery, null);
    }

    /**
     * @param workers the registry the {@code CapabilityWorker} service registers
     *                into, or {@code null} to serve callers only. Handing one in
     *                is what lets business code in another process be invoked as
     *                a capability (§26.1).
     */
    public PipeMeshServer(
            WorkflowRuntime runtime,
            ExecutionUpdateBroker broker,
            int port,
            RecoveryScheduler recovery,
            WorkerRegistry workers) {

        this(runtime, broker, port, recovery, workers, PrincipalResolver.ANONYMOUS);
    }

    /**
     * @param principals how a remote caller's identity is established. The default
     *                   makes every caller anonymous — safe, and visibly so: a
     *                   capability that asks for a permission simply will not run
     *                   until an application supplies a real resolver.
     */
    public PipeMeshServer(
            WorkflowRuntime runtime,
            ExecutionUpdateBroker broker,
            int port,
            RecoveryScheduler recovery,
            WorkerRegistry workers,
            PrincipalResolver principals) {

        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(principals, "principal resolver");
        Objects.requireNonNull(broker, "broker");
        this.recovery = recovery;

        ServerBuilder<?> builder = ServerBuilder.forPort(port)
                .intercept(new CallMetadata())
                .addService(new PipeMeshService(runtime, broker, principals));
        if (workers != null) {
            builder.addService(new CapabilityWorkerService(workers));
        }
        this.server = builder.build();
    }

    public PipeMeshServer start() throws IOException {
        server.start();
        if (recovery != null) {
            recovery.start();
        }
        // A JVM killed without shutting down leaves executions RUNNING for the
        // recovery sweeper to find. Closing cleanly is cheaper for everyone.
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
        return this;
    }

    /** The port actually bound, which is the useful one when 0 was asked for. */
    public int port() {
        return server.getPort();
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    @Override
    public void close() {
        if (recovery != null) {
            recovery.close();
        }
        server.shutdown();
        try {
            if (!server.awaitTermination(10, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        }
    }
}
