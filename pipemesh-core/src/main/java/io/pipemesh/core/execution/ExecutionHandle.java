package io.pipemesh.core.execution;

import io.pipemesh.core.workflow.StepId;

import java.util.Objects;
import java.util.Optional;

/**
 * What {@code start} and {@code resume} hand back: where the execution got to,
 * nothing more. Deliberately not a future or a live object — the caller may be
 * on the far side of a network boundary (§26.1).
 */
public record ExecutionHandle(ExecutionId executionId, ExecutionStatus status, StepId currentStep) {

    public ExecutionHandle {
        Objects.requireNonNull(executionId, "execution id");
        Objects.requireNonNull(status, "status");
    }

    public Optional<StepId> currentStepIfAny() {
        return Optional.ofNullable(currentStep);
    }
}
