package io.pipemesh.core.execution;

import io.pipemesh.core.workflow.WorkflowId;

import java.util.Objects;
import java.util.Optional;

/**
 * Everything needed to start an execution.
 *
 * <p>A request object rather than a widening parameter list: an idempotency key,
 * a deadline, a principal and a priority are all plausible next fields, and each
 * would otherwise break every caller and the proto binding with it.
 *
 * <p>{@code traceParent} lets a caller that is already inside a trace pass it in,
 * so the workflow appears underneath the request that asked for it rather than
 * as an unrelated trace of its own.
 */
public record ExecutionRequest(
        WorkflowId workflowId,
        ExecutionInput input,
        OrganizationId organization,
        String traceParent) {

    public ExecutionRequest {
        Objects.requireNonNull(workflowId, "workflow id");
        input = input == null ? ExecutionInput.empty() : input;
        organization = organization == null ? OrganizationId.DEFAULT : organization;
    }

    public static ExecutionRequest of(WorkflowId workflowId, ExecutionInput input) {
        return new ExecutionRequest(workflowId, input, OrganizationId.DEFAULT, null);
    }

    public static ExecutionRequest of(
            WorkflowId workflowId, ExecutionInput input, OrganizationId organization) {
        return new ExecutionRequest(workflowId, input, organization, null);
    }

    public ExecutionRequest within(String traceParent) {
        return new ExecutionRequest(workflowId, input, organization, traceParent);
    }

    public Optional<String> traceParentIfAny() {
        return Optional.ofNullable(traceParent).filter(value -> !value.isBlank());
    }
}
