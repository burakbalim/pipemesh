package io.pipemesh.grpc;

import io.grpc.stub.ServerCallStreamObserver;
import io.pipemesh.proto.v1.ExecutionUpdate;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Carries updates from the thread running a workflow to the thread writing them
 * out.
 *
 * <p>Without this the broker writes to the gRPC stream on whichever thread just
 * advanced the execution, and a watcher that is slow — or simply not reading
 * yet — can block that thread. An observer must never fail an execution, and
 * holding one up is worse than failing it: nothing reports a hang.
 *
 * <p>The queue is bounded and drops the oldest update when a watcher cannot keep
 * up. Dropping is the honest trade: the alternatives are growing without limit
 * until the runtime runs out of memory, or slowing the runtime down to the speed
 * of its slowest observer. A watcher that misses updates can always read the
 * execution's current state.
 */
final class UpdatePump implements AutoCloseable {

    private static final int CAPACITY = 256;
    private static final ExecutionUpdate CLOSED = ExecutionUpdate.getDefaultInstance();

    private final BlockingQueue<ExecutionUpdate> pending = new LinkedBlockingQueue<>(CAPACITY);
    private final ServerCallStreamObserver<ExecutionUpdate> call;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread writer;

    UpdatePump(ServerCallStreamObserver<ExecutionUpdate> call) {
        this.call = call;
        this.writer = Thread.ofVirtual().start(this::drain);
    }

    /** Never blocks the caller, whatever the watcher is doing. */
    void offer(ExecutionUpdate update) {
        if (!running.get()) {
            return;
        }
        while (!pending.offer(update)) {
            pending.poll();
        }
    }

    private void drain() {
        try {
            while (running.get()) {
                ExecutionUpdate update = pending.poll(200, TimeUnit.MILLISECONDS);
                if (update == null) {
                    continue;
                }
                if (update == CLOSED) {
                    return;
                }
                if (call.isCancelled()) {
                    return;
                }
                call.onNext(update);
                if (update.getUpdateCase() == ExecutionUpdate.UpdateCase.FINISHED) {
                    call.onCompleted();
                    return;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException clientWentAway) {
            // Writing to a stream nobody is reading any more is not an error worth
            // propagating into the runtime.
        }
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            pending.offer(CLOSED);
        }
    }
}
