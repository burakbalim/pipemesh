package io.pipemesh.core.state;

import com.fasterxml.jackson.databind.JsonNode;
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;

import java.util.Objects;

/**
 * One entry of an execution's step history — the durable half of a trace (§22).
 * Token counts and prompt version are only set for model-backed steps; whatever
 * else a step reported about itself is kept verbatim in {@code attributes}.
 *
 * <p>{@code attempt} counts from 1. Every attempt is its own row rather than a
 * counter on one row: how many times something was tried, and how long each try
 * took before giving up, is exactly what someone reads this table to find out.
 */
public record StepRecord(
        ExecutionId executionId,
        StepId stepId,
        StepType stepType,
        StepOutcome outcome,
        JsonNode input,
        JsonNode output,
        String modelId,
        String promptVersion,
        long inputTokens,
        long outputTokens,
        long latencyMillis,
        long startedAtEpochMillis,
        long finishedAtEpochMillis,
        JsonNode attributes,
        int attempt) {

    public StepRecord {
        Objects.requireNonNull(executionId, "execution id");
        Objects.requireNonNull(stepId, "step id");
        Objects.requireNonNull(stepType, "step type");
        Objects.requireNonNull(outcome, "outcome");
    }

    public enum StepOutcome {
        SUCCESS,
        FAILED,
        SUSPENDED
    }
}
