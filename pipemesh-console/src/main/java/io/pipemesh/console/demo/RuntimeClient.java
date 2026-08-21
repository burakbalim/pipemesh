package io.pipemesh.console.demo;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.pipemesh.proto.v1.ExecutionHandle;
import io.pipemesh.proto.v1.ExecutionUpdate;
import io.pipemesh.proto.v1.PipeMeshGrpc;
import io.pipemesh.proto.v1.StartExecutionRequest;
import io.pipemesh.proto.v1.WatchExecutionRequest;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Iterator;

/**
 * The console talking to the runtime, exactly as any other client does.
 *
 * <p>There is no private door. The console holds no special credential and the
 * runtime grants it no standing — it presents an API key over the same boundary
 * an SDK uses, so what the demo screen proves is the real path rather than a
 * rehearsal of one (§26.1).
 */
@Component
public class RuntimeClient implements AutoCloseable {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final ManagedChannel channel;

    public RuntimeClient(@Value("${console.runtime.target:localhost:8080}") String target) {
        this.channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
    }

    public ExecutionHandle start(String apiKey, StartExecutionRequest request) {
        return stub(apiKey).startExecution(request);
    }

    /**
     * The live updates, as the runtime produces them.
     *
     * <p>Returned as an iterator the caller must drain: an unread server stream
     * applies backpressure across the connection, which is how a forgotten stream
     * stops being only its own problem.
     */
    public Iterator<ExecutionUpdate> watch(String apiKey, WatchExecutionRequest request) {
        return stub(apiKey).watchExecution(request);
    }

    private PipeMeshGrpc.PipeMeshBlockingStub stub(String apiKey) {
        Metadata metadata = new Metadata();
        metadata.put(AUTHORIZATION, "Bearer " + apiKey);

        return PipeMeshGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    @PreDestroy
    @Override
    public void close() {
        channel.shutdown();
    }
}
