package io.pipemesh.core.observability;

import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.WorkflowId;
import io.pipemesh.core.workflow.WorkflowVersion;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Where an execution stands, at the moment something happened to it. */
public record ExecutionEvent(
        ExecutionId executionId,
        OrganizationId organization,
        WorkflowId workflowId,
        WorkflowVersion workflowVersion,
        ExecutionStatus status,
        StepId currentStep,
        TraceContext trace,
        long atEpochMillis) {

    public ExecutionEvent {
        Objects.requireNonNull(executionId, "execution id");
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(workflowId, "workflow id");
        Objects.requireNonNull(status, "status");
    }

    public Optional<StepId> currentStepIfAny() {
        return Optional.ofNullable(currentStep);
    }

    public Optional<TraceContext> traceIfAny() {
        return Optional.ofNullable(trace);
    }

    /** The event as attributes, ready to become span attributes or metric labels. */
    public Map<String, String> attributes() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(TelemetryAttributes.ORGANIZATION, organization.value());
        attributes.put(TelemetryAttributes.WORKFLOW_ID, workflowId.value());
        attributes.put(TelemetryAttributes.WORKFLOW_VERSION, workflowVersion.value());
        attributes.put(TelemetryAttributes.EXECUTION_ID, executionId.value());
        attributes.put(TelemetryAttributes.EXECUTION_STATUS, status.name());
        currentStepIfAny().ifPresent(step -> attributes.put(TelemetryAttributes.STEP_ID, step.value()));
        return Map.copyOf(attributes);
    }
}
