package io.pipemesh.core.observability;

import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A step about to run (§30).
 *
 * <p>Separate from {@link StepEvent} rather than that record with nulls in it: a
 * step that has not run has no outcome and no latency, and a type whose fields
 * only sometimes mean something is a type that has to be read twice.
 *
 * <p>Published before anything is persisted, which is safe for the reason a
 * sub-step event would not be: durable state already says the execution stands
 * at this step, so what a watcher sees here has a record behind it. An event
 * about work inside a step would have no such witness — it would be the only
 * one, and a crash would make it a liar.
 *
 * <p>{@code attempt} counts from 1. A retry is a real second start and says so,
 * rather than looking like one long step.
 */
public record StepStartEvent(
        ExecutionEvent execution, StepId stepId, StepType stepType, int attempt) {

    public StepStartEvent {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(stepId, "step id");
        Objects.requireNonNull(stepType, "step type");
    }

    public Map<String, String> attributes() {
        Map<String, String> attributes = new LinkedHashMap<>(execution.attributes());
        attributes.put(TelemetryAttributes.STEP_ID, stepId.value());
        attributes.put(TelemetryAttributes.STEP_TYPE, stepType.name());
        attributes.put(TelemetryAttributes.STEP_ATTEMPT, String.valueOf(attempt));
        return Map.copyOf(attributes);
    }
}
