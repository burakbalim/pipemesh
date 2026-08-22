package io.pipemesh.grpc;

import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.observability.ExecutionEvent;
import io.pipemesh.core.observability.ExecutionObserver;
import io.pipemesh.core.observability.RecoveryEvent;
import io.pipemesh.core.observability.StepEvent;
import io.pipemesh.core.observability.StepStartEvent;
import io.pipemesh.core.observability.TokenEvent;
import io.pipemesh.proto.v1.ExecutionFinished;
import io.pipemesh.proto.v1.ExecutionRecovered;
import io.pipemesh.proto.v1.ExecutionResumed;
import io.pipemesh.proto.v1.ExecutionSuspended;
import io.pipemesh.proto.v1.ExecutionUpdate;
import io.pipemesh.proto.v1.StepFinished;
import io.pipemesh.proto.v1.StepStarted;
import io.pipemesh.proto.v1.UpdateKind;
import io.pipemesh.proto.v1.TokenChunk;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
public final class ExecutionUpdateBroker implements ExecutionObserver, AutoCloseable {

    /**
     * One subscription and what it declined.
     *
     * <p>The filter is per watcher, not per execution: two clients may want
     * different amounts of the same run, and the events are produced either way
     * because telemetry reads the same channel (§22.1).
     */
    private record Watcher(Consumer<ExecutionUpdate> onUpdate, Set<UpdateKind> excluded) {

        boolean wants(UpdateKind kind) {
            return kind == null || !excluded.contains(kind);
        }
    }

    private final Map<ExecutionId, List<Watcher>> watchers = new ConcurrentHashMap<>();
    private final UpdateChannel channel;
    private final AutoCloseable subscription;

    public ExecutionUpdateBroker() {
        this(UpdateChannel.NONE);
    }

    /**
     * @param channel how updates reach other processes. {@link UpdateChannel#NONE}
     *                — the default — keeps everything local, which is correct
     *                while there is one process and wrong the moment there are
     *                two (§30.1).
     */
    public ExecutionUpdateBroker(UpdateChannel channel) {
        this.channel = Objects.requireNonNull(channel, "update channel");
        // Arriving updates go to local watchers and are not published again;
        // republishing what you were told is how a message loop starts.
        this.subscription = channel.subscribe(this::toLocalWatchers);
    }

    @Override
    public void close() {
        try {
            subscription.close();
        } catch (Exception ignored) {
            // Nothing a caller can do about a listener that will not let go.
        }
    }

    /** @return a handle that stops the subscription; a watcher that goes away must call it */
    public AutoCloseable watch(ExecutionId executionId, Consumer<ExecutionUpdate> onUpdate) {
        return watch(executionId, Set.of(), onUpdate);
    }

    /**
     * @param excluded kinds this watcher does not want. Empty means everything:
     *                 an unset filter cannot be allowed to mean silence, or every
     *                 client that never heard of filtering would go quiet.
     */
    public AutoCloseable watch(
            ExecutionId executionId, Set<UpdateKind> excluded, Consumer<ExecutionUpdate> onUpdate) {

        Watcher watcher = new Watcher(onUpdate, Set.copyOf(excluded));
        watchers.computeIfAbsent(executionId, id -> new CopyOnWriteArrayList<>()).add(watcher);
        return () -> {
            List<Watcher> subscribers = watchers.get(executionId);
            if (subscribers != null) {
                subscribers.remove(watcher);
                if (subscribers.isEmpty()) {
                    watchers.remove(executionId);
                }
            }
        };
    }

    @Override
    public void stepStarted(StepStartEvent event) {
        deliver(event.execution().executionId(), UpdateKind.UPDATE_KIND_PROGRESS,
                update -> update.setStepStarted(StepStarted.newBuilder()
                        .setStepId(event.stepId().value())
                        .setStepType(event.stepType().name())
                        .setAttempt(event.attempt())
                        .build()));
    }

    @Override
    public void executionRecovered(RecoveryEvent event) {
        publish(event.execution(), update -> update.setRecovered(ExecutionRecovered.newBuilder()
                .setStepId(event.execution().currentStepIfAny().map(StepId::value).orElse(""))
                .setRepeated(event.repeated())
                .setReason(event.reason())
                .build()));
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
        deliver(event.executionId(), UpdateKind.UPDATE_KIND_TOKEN,
                builder -> builder.setToken(TokenChunk.newBuilder()
                .setStepId(event.stepId().value())
                .setText(event.text())
                .build()));
    }

    private void publish(ExecutionEvent event, Consumer<ExecutionUpdate.Builder> fill) {
        deliver(event.executionId(), null, fill);
    }

    /**
     * @param kind which filter group this update belongs to, or {@code null} for
     *             one nobody may decline — execution status is what a watcher
     *             came for, and a stream that could omit "finished" would leave
     *             a client waiting for something already over
     */
    private void deliver(
            ExecutionId executionId, UpdateKind kind, Consumer<ExecutionUpdate.Builder> fill) {

        ExecutionUpdate.Builder builder = ExecutionUpdate.newBuilder()
                .setAt(WireTypes.toWire(System.currentTimeMillis()));
        fill.accept(builder);
        ExecutionUpdate update = builder.build();

        // Published even when nobody here is watching: the watcher may be on
        // another process entirely, which is the whole point of a channel.
        channel.publish(executionId, update);
        toLocalWatchers(executionId, update);
    }

    /**
     * Hands an update to this process's own watchers, unnumbered.
     *
     * <p>Numbering belongs to whoever serves a stream, not here. A sequence
     * describes one stream (§30.2), and a watcher's stream may begin with
     * replayed history — a counter shared by every watcher of an execution would
     * hand the second one numbers that start halfway through, and a replaying one
     * numbers that go backwards.
     *
     * <p>Filtering still happens here, before anything is written, so a filtered
     * watcher sees gaps in whatever numbering it is given — and a gap says "not
     * for you", which is what tells filtering apart from loss.
     */
    private void toLocalWatchers(ExecutionId executionId, ExecutionUpdate update) {
        List<Watcher> subscribers = watchers.get(executionId);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }

        UpdateKind kind = kindOf(update);
        subscribers.stream()
                .filter(subscriber -> subscriber.wants(kind))
                .forEach(subscriber -> subscriber.onUpdate().accept(update));
    }

    /**
     * Which filter group an update belongs to, read from the update itself.
     *
     * <p>Derived rather than carried, so the rule is the same on the process that
     * produced it and the one serving a watcher. Anything not named here cannot
     * be declined: execution status is what a watcher came for, and a stream that
     * could omit "finished" would leave a client waiting for something over.
     */
    private static UpdateKind kindOf(ExecutionUpdate update) {
        return switch (update.getUpdateCase()) {
            case TOKEN -> UpdateKind.UPDATE_KIND_TOKEN;
            case STEP_STARTED -> UpdateKind.UPDATE_KIND_PROGRESS;
            default -> null;
        };
    }
}
