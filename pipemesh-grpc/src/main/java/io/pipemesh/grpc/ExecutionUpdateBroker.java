package io.pipemesh.grpc;

import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.observability.ExecutionEvent;
import io.pipemesh.core.observability.ExecutionObserver;
import io.pipemesh.core.observability.StepEvent;
import io.pipemesh.core.observability.TokenEvent;
import io.pipemesh.proto.v1.ExecutionFinished;
import io.pipemesh.proto.v1.ExecutionResumed;
import io.pipemesh.proto.v1.ExecutionSuspended;
import io.pipemesh.proto.v1.ExecutionUpdate;
import io.pipemesh.proto.v1.StepFinished;
import io.pipemesh.proto.v1.TokenChunk;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Turns execution events into the stream {@code WatchExecution} serves.
 *
 * <p>This is the whole reason tokens and execution events share one observer
 * channel: the gRPC boundary is a single subscriber to that channel, not a second
 * fan-out mechanism bolted alongside it (§26.4).
 *
 * <p>Live only, and numbered from 1. Sequence 0 belongs to the service: it is the
 * snapshot a watcher receives on arrival, so that "from here I am listening" is a
 * moment the client can point at.
 *
 * <p>The proto's {@code from_sequence} is not honoured. Replaying means storing
 * updates and deciding how long to keep them — a decision worth making
 * deliberately rather than as a side effect of adding a stream.
 */
public final class ExecutionUpdateBroker implements ExecutionObserver {

    private final Map<ExecutionId, List<Consumer<ExecutionUpdate>>> watchers = new ConcurrentHashMap<>();
    private final Map<ExecutionId, AtomicLong> sequences = new ConcurrentHashMap<>();

    /** @return a handle that stops the subscription; a watcher that goes away must call it */
    public AutoCloseable watch(ExecutionId executionId, Consumer<ExecutionUpdate> onUpdate) {
        watchers.computeIfAbsent(executionId, id -> new CopyOnWriteArrayList<>()).add(onUpdate);
        return () -> {
            List<Consumer<ExecutionUpdate>> subscribers = watchers.get(executionId);
            if (subscribers != null) {
                subscribers.remove(onUpdate);
                if (subscribers.isEmpty()) {
                    watchers.remove(executionId);
                    sequences.remove(executionId);
                }
            }
        };
    }

    @Override
    public void stepFinished(StepEvent event) {
        publish(event.execution(), update -> update.setStepFinished(StepFinished.newBuilder()
                .setStepId(event.stepId().value())
                .setStepType(event.stepType().name())
                .setOutcome(WireTypes.toWire(event.outcome()))
                .setLatencyMs(event.latencyMillis())
                .build()));
    }

    @Override
    public void executionSuspended(ExecutionEvent event) {
        publish(event, update -> update.setSuspended(ExecutionSuspended.newBuilder()
                .setStepId(event.currentStepIfAny().map(step -> step.value()).orElse(""))
                .build()));
    }

    @Override
    public void executionResumed(ExecutionEvent event) {
        publish(event, update -> update.setResumed(ExecutionResumed.newBuilder()
                .setStepId(event.currentStepIfAny().map(step -> step.value()).orElse(""))
                .setWaitedMs(event.sinceLastWriteMillis())
                .build()));
    }

    @Override
    public void executionFinished(ExecutionEvent event) {
        publish(event, update -> update.setFinished(ExecutionFinished.newBuilder()
                .setStatus(WireTypes.toWire(event.status()))
                .build()));
    }

    @Override
    public void tokenProduced(TokenEvent event) {
        deliver(event.executionId(), builder -> builder.setToken(TokenChunk.newBuilder()
                .setStepId(event.stepId().value())
                .setText(event.text())
                .build()));
    }

    private void publish(ExecutionEvent event, Consumer<ExecutionUpdate.Builder> fill) {
        deliver(event.executionId(), fill);
    }

    private void deliver(ExecutionId executionId, Consumer<ExecutionUpdate.Builder> fill) {
        List<Consumer<ExecutionUpdate>> subscribers = watchers.get(executionId);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        ExecutionUpdate.Builder builder = ExecutionUpdate.newBuilder()
                .setSequence(sequences.computeIfAbsent(executionId, id -> new AtomicLong())
                        .incrementAndGet())
                .setAt(WireTypes.toWire(System.currentTimeMillis()));
        fill.accept(builder);

        ExecutionUpdate update = builder.build();
        subscribers.forEach(subscriber -> subscriber.accept(update));
    }
}
