package io.pipemesh.core.observability;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Sends the same telemetry to several backends at once — Datadog and New Relic
 * and a log file, if that is what an organization runs.
 *
 * <p>Each observer is isolated: one exporter failing does not stop the others,
 * and none of them can fail an execution.
 */
public final class CompositeExecutionObserver implements ExecutionObserver {

    private final List<ExecutionObserver> observers;
    private final Consumer<Throwable> onFailure;

    public CompositeExecutionObserver(List<ExecutionObserver> observers) {
        this(observers, failure -> {
        });
    }

    /**
     * @param onFailure told about an observer that threw — a place to log it,
     *                  since silently dropping telemetry failures is how a broken
     *                  exporter goes unnoticed for a month
     */
    public CompositeExecutionObserver(List<ExecutionObserver> observers, Consumer<Throwable> onFailure) {
        this.observers = List.copyOf(Objects.requireNonNull(observers, "observers"));
        this.onFailure = Objects.requireNonNull(onFailure, "failure handler");
    }

    public static ExecutionObserver of(ExecutionObserver... observers) {
        return new CompositeExecutionObserver(List.of(observers));
    }

    /** Wraps a single observer so that whatever it throws stays contained. */
    public static ExecutionObserver guarded(ExecutionObserver observer) {
        return observer instanceof CompositeExecutionObserver
                ? observer
                : new CompositeExecutionObserver(List.of(observer));
    }

    @Override
    public void executionStarted(ExecutionEvent event) {
        each(observer -> observer.executionStarted(event));
    }

    @Override
    public void stepStarted(StepStartEvent event) {
        each(observer -> observer.stepStarted(event));
    }

    @Override
    public void stepFinished(StepEvent event) {
        each(observer -> observer.stepFinished(event));
    }

    @Override
    public void executionSuspended(ExecutionEvent event) {
        each(observer -> observer.executionSuspended(event));
    }

    @Override
    public void executionResumed(ExecutionEvent event) {
        each(observer -> observer.executionResumed(event));
    }

    @Override
    public void executionFinished(ExecutionEvent event) {
        each(observer -> observer.executionFinished(event));
    }

    @Override
    public void executionRecovered(RecoveryEvent event) {
        each(observer -> observer.executionRecovered(event));
    }

    @Override
    public void tokenProduced(TokenEvent event) {
        each(observer -> observer.tokenProduced(event));
    }

    private void each(Consumer<ExecutionObserver> notification) {
        for (ExecutionObserver observer : observers) {
            try {
                notification.accept(observer);
            } catch (RuntimeException failure) {
                onFailure.accept(failure);
            }
        }
    }
}
