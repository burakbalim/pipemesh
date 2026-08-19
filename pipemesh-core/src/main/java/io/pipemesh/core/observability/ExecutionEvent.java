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

/**
 * Where an execution stands, at the moment something happened to it.
 *
 * <p>{@code startedAtEpochMillis} and {@code lastWrittenAtEpochMillis} come from
 * the stored record rather than from a timer, which is what lets an exporter
 * measure a wait that outlived the process that began it: on resume, the gap
 * since the last write <em>is</em> how long the execution sat there.
 */
public record ExecutionEvent(
        ExecutionId executionId,
        OrganizationId organization,
        WorkflowId workflowId,
        WorkflowVersion workflowVersion,
        ExecutionStatus status,
        StepId currentStep,
        TraceContext trace,
        long atEpochMillis,
        long startedAtEpochMillis,
        long lastWrittenAtEpochMillis) {

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

    /** How long this execution has been alive, including any time it spent waiting. */
    public long ageMillis() {
        return startedAtEpochMillis <= 0 ? 0 : Math.max(0, atEpochMillis - startedAtEpochMillis);
    }

    /** How long it sat since the last write — on a resume, that is the wait. */
    public long sinceLastWriteMillis() {
        return lastWrittenAtEpochMillis <= 0 ? 0 : Math.max(0, atEpochMillis - lastWrittenAtEpochMillis);
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
