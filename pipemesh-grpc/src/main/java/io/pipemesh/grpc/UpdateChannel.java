package io.pipemesh.grpc;

import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.proto.v1.ExecutionUpdate;

import java.util.function.BiConsumer;

/**
 * Carries execution updates between processes (§30.1).
 *
 * <p>Without one, a watcher only ever hears about executions its own process is
 * driving — correct on a single node, and a silent failure the moment there are
 * two: no error, just a stream that says nothing.
 *
 * <p>{@link #NONE} is the default and is exactly today's behaviour. An
 * implementation is a deployment concern: which transport carries this is not
 * something the boundary should know, and a runtime without a database still has
 * to work.
 */
public interface UpdateChannel {

    /** No channel: local delivery only, which is right when there is one process. */
    UpdateChannel NONE = new UpdateChannel() {

        @Override
        public void publish(ExecutionId executionId, ExecutionUpdate update) {
        }

        @Override
        public AutoCloseable subscribe(BiConsumer<ExecutionId, ExecutionUpdate> onUpdate) {
            return () -> {
            };
        }
    };

    /**
     * Sends an update to whatever other processes are listening.
     *
     * <p>The update carries no sequence number. Numbering belongs to the process
     * serving a watcher, because a sequence describes one stream rather than the
     * execution — two processes cannot share a counter without sharing a lock,
     * and a client only ever reads one of them.
     *
     * <p>Must not throw in a way that reaches the caller: this is called from an
     * observer, and an observer may never fail an execution (§22.1).
     */
    void publish(ExecutionId executionId, ExecutionUpdate update);

    /** @return a handle that stops listening */
    AutoCloseable subscribe(BiConsumer<ExecutionId, ExecutionUpdate> onUpdate);
}
