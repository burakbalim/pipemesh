package io.pipemesh.core.observability;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes telemetry to the JDK's own logging, which every deployment already has.
 *
 * <p>Not a substitute for a real backend — it exists so that a new deployment is
 * observable before anyone has wired an exporter, and so a broken exporter can be
 * compared against something.
 */
public final class LoggingExecutionObserver implements ExecutionObserver {

    private final Logger log;
    private final Level level;

    public LoggingExecutionObserver() {
        this(System.getLogger("io.pipemesh.execution"), Level.INFO);
    }

    public LoggingExecutionObserver(Logger log, Level level) {
        this.log = log;
        this.level = level;
    }

    @Override
    public void executionStarted(ExecutionEvent event) {
        write("execution.started", event.attributes());
    }

    @Override
    public void stepFinished(StepEvent event) {
        write("step.finished " + event.latencyMillis() + "ms", event.attributes());
    }

    @Override
    public void executionSuspended(ExecutionEvent event) {
        write("execution.suspended", event.attributes());
    }

    @Override
    public void executionResumed(ExecutionEvent event) {
        write("execution.resumed", event.attributes());
    }

    @Override
    public void executionFinished(ExecutionEvent event) {
        write("execution.finished", event.attributes());
    }

    private void write(String what, Map<String, String> attributes) {
        log.log(level, what + " " + attributes.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(" ")));
    }
}
