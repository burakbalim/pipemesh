package io.pipemesh.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.pipemesh.core.dispatch.ExecutionLeases;

import java.util.Objects;

/**
 * Publishes how much work is waiting for a driver (§22.1).
 *
 * <p>Two gauges, and only one of them is for an autoscaler:
 *
 * <ul>
 *   <li>{@code backlog.age_seconds} — how long the front of the queue has been
 *       there. This is the delay somebody is experiencing, and a target for it
 *       ("nothing waits more than ten seconds") is a sentence a person can
 *       defend.
 *   <li>{@code backlog.size} — how many are queued. For a dashboard: it feeds
 *       back on itself when scaled on, and says nothing about whether the number
 *       is a problem.
 * </ul>
 *
 * <p><b>Do not sum these across instances.</b> Every process reports the same
 * fact about the same database, so a sum multiplies it by the replica count.
 * That is why there is no instance attribute: a gauge that carries one is a
 * gauge something will eventually add up.
 *
 * <p>Asynchronous, so nothing here runs on a schedule of ours — the SDK asks
 * when it collects. A callback that throws costs that collection and nothing
 * else, which is the observer rule (§22.1) coming for free.
 */
public final class OpenTelemetryBacklogGauge implements AutoCloseable {

    private static final String SCOPE = "io.pipemesh";

    private final AutoCloseable age;
    private final AutoCloseable size;

    public OpenTelemetryBacklogGauge(OpenTelemetry openTelemetry, ExecutionLeases leases) {
        Objects.requireNonNull(leases, "leases");
        Meter meter = openTelemetry.getMeter(SCOPE);

        this.age = meter.gaugeBuilder("pipemesh.backlog.age_seconds")
                .setDescription("How long the oldest unclaimed execution has been waiting")
                .setUnit("s")
                .buildWithCallback(measurement ->
                        measurement.record(leases.backlog().oldestWaitingMillis() / 1000.0));

        this.size = meter.gaugeBuilder("pipemesh.backlog.size")
                .setDescription("Executions waiting for a driver")
                .ofLongs()
                .buildWithCallback(measurement ->
                        measurement.record(leases.backlog().size()));
    }

    @Override
    public void close() {
        closeQuietly(age);
        closeQuietly(size);
    }

    private static void closeQuietly(AutoCloseable gauge) {
        try {
            gauge.close();
        } catch (Exception ignored) {
            // Unregistering an instrument cannot fail in a way a caller can act on.
        }
    }
}
