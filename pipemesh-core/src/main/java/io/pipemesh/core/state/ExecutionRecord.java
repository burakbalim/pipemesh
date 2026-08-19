package io.pipemesh.core.state;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.WorkflowId;
import io.pipemesh.core.workflow.WorkflowVersion;

import java.util.Objects;

/**
 * The persisted form of an execution — enough to rebuild it after a restart.
 *
 * <p>{@code version} carries optimistic locking: a store must reject a write
 * whose version no longer matches, which is what stops two workers from
 * advancing the same execution twice.
 *
 * <p>Timestamps are set by the store, not by the caller: a record's idea of
 * "now" should come from wherever the row actually lands.
 *
 * <p>{@code traceContext} is persisted with the state on purpose. An execution
 * resumed after a restart has to attach to the same trace, or observability
 * breaks exactly where it matters most (§22).
 */
public record ExecutionRecord(
        ExecutionId executionId,
        OrganizationId organization,
        WorkflowId workflowId,
        WorkflowVersion workflowVersion,
        ExecutionStatus status,
        StepId currentStep,
        ObjectNode variables,
        String traceContext,
        long version,
        long createdAtEpochMillis,
        long updatedAtEpochMillis) {

    public ExecutionRecord {
        Objects.requireNonNull(executionId, "execution id");
        organization = organization == null ? OrganizationId.DEFAULT : organization;
        Objects.requireNonNull(workflowId, "workflow id");
        Objects.requireNonNull(workflowVersion, "workflow version");
        Objects.requireNonNull(status, "status");
        variables = variables == null ? JsonNodeFactory.instance.objectNode() : variables.deepCopy();
    }

    @Override
    public ObjectNode variables() {
        return variables.deepCopy();
    }
}
