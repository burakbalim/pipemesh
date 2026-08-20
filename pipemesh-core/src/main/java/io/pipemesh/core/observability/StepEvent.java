package io.pipemesh.core.observability;

import com.fasterxml.jackson.databind.JsonNode;
import io.pipemesh.core.state.StepRecord;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One step's run.
 *
 * <p>{@code reported} is whatever the step said about itself — tokens, model,
 * which capability it reached. It travels through untouched, so a backend can
 * chart something the engine has never heard of.
 *
 * <p>{@code attempt} counts from 1, so a dashboard can separate "slow" from
 * "failed twice and then worked".
 */
public record StepEvent(
        ExecutionEvent execution,
        StepId stepId,
        StepType stepType,
        StepRecord.StepOutcome outcome,
        long latencyMillis,
        Map<String, JsonNode> reported,
        int attempt) {

    public StepEvent {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(stepId, "step id");
        Objects.requireNonNull(stepType, "step type");
        Objects.requireNonNull(outcome, "outcome");
        reported = Map.copyOf(reported == null ? Map.of() : reported);
    }

    public Map<String, String> attributes() {
        Map<String, String> attributes = new LinkedHashMap<>(execution.attributes());
        attributes.put(TelemetryAttributes.STEP_ID, stepId.value());
        attributes.put(TelemetryAttributes.STEP_TYPE, stepType.name());
        attributes.put(TelemetryAttributes.STEP_OUTCOME, outcome.name());
        attributes.put(TelemetryAttributes.STEP_ATTEMPT, String.valueOf(attempt));
        reported.forEach((name, value) -> attributes.put(name, value.asText()));
        return Map.copyOf(attributes);
    }
}
