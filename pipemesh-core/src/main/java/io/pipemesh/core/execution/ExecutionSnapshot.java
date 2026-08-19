package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.WorkflowId;
import io.pipemesh.core.workflow.WorkflowVersion;

import java.util.Objects;
import java.util.Optional;

/**
 * A read-only view of an execution, for polling and for observability.
 *
 * <p>Timestamps are epoch millis and the status travels as a plain enum name, so
 * nothing here needs a Java-specific type to be reconstructed on the other side
 * of the wire (§26.1).
 */
public record ExecutionSnapshot(
        ExecutionId executionId,
        WorkflowId workflowId,
        WorkflowVersion workflowVersion,
        ExecutionStatus status,
        StepId currentStep,
        ObjectNode variables,
        long createdAtEpochMillis,
        long updatedAtEpochMillis) {

    public ExecutionSnapshot {
        Objects.requireNonNull(executionId, "execution id");
        Objects.requireNonNull(workflowId, "workflow id");
        Objects.requireNonNull(workflowVersion, "workflow version");
        Objects.requireNonNull(status, "status");
        variables = variables == null ? JsonNodeFactory.instance.objectNode() : variables.deepCopy();
    }

    @Override
    public ObjectNode variables() {
        return variables.deepCopy();
    }

    public Optional<StepId> currentStepIfAny() {
        return Optional.ofNullable(currentStep);
    }
}
