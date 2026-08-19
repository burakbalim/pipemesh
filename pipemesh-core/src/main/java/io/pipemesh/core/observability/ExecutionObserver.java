package io.pipemesh.core.observability;

/**
 * Where execution telemetry goes.
 *
 * <p>One narrow interface rather than an adapter per vendor. Datadog, New Relic,
 * Honeycomb and Grafana all ingest OpenTelemetry, so the useful implementation is
 * an OTLP one and a vendor module is only needed by someone who wants a native
 * API. Running several at once is what {@link CompositeExecutionObserver} is for.
 *
 * <p>Every method has a default, so a later event — a retry, a branch join, a
 * budget warning — can be added without breaking an implementation that was
 * written today.
 *
 * <p>An observer must not change what an execution does. Whatever it throws is
 * swallowed before it reaches the engine: telemetry going dark is a bad day,
 * telemetry taking a workflow down is an outage.
 */
public interface ExecutionObserver {

    ExecutionObserver NONE = new ExecutionObserver() {
    };

    default void executionStarted(ExecutionEvent event) {
    }

    default void stepFinished(StepEvent event) {
    }

    default void executionSuspended(ExecutionEvent event) {
    }

    default void executionResumed(ExecutionEvent event) {
    }

    default void executionFinished(ExecutionEvent event) {
    }
}
