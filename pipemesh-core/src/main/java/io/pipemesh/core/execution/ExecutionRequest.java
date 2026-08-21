package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.JsonNode;
import io.pipemesh.core.capability.Principal;
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
        String traceParent,
        JsonNode intent,
        Principal principal) {

    public ExecutionRequest(
            WorkflowId workflowId, ExecutionInput input,
            OrganizationId organization, String traceParent) {

        this(workflowId, input, organization, traceParent, null, Principal.SYSTEM);
    }

    public ExecutionRequest(
            WorkflowId workflowId, ExecutionInput input,
            OrganizationId organization, String traceParent, JsonNode intent) {

        this(workflowId, input, organization, traceParent, intent, Principal.SYSTEM);
    }

    public ExecutionRequest {
        Objects.requireNonNull(workflowId, "workflow id");
        input = input == null ? ExecutionInput.empty() : input;
        organization = organization == null ? OrganizationId.DEFAULT : organization;
        principal = principal == null ? Principal.SYSTEM : principal;
    }

    public static ExecutionRequest of(WorkflowId workflowId, ExecutionInput input) {
        return new ExecutionRequest(workflowId, input, OrganizationId.DEFAULT, null);
    }

    public static ExecutionRequest of(
            WorkflowId workflowId, ExecutionInput input, OrganizationId organization) {
        return new ExecutionRequest(workflowId, input, organization, null);
    }

    public ExecutionRequest within(String traceParent) {
        return new ExecutionRequest(workflowId, input, organization, traceParent, intent, principal);
    }

    /**
     * Runs this on behalf of somebody in particular.
     *
     * <p>Set by whoever authenticated them. A request that arrived over a network
     * never carries its own answer to this (§23).
     */
    public ExecutionRequest onBehalfOf(Principal principal) {
        return new ExecutionRequest(workflowId, input, organization, traceParent, intent, principal);
    }

    /**
     * Records how this execution came to be started.
     *
     * <p>An execution begun from a natural-language message should be able to say
     * which intent was read from it and how — otherwise "why did this run?" has no
     * answer anywhere (§19).
     */
    public ExecutionRequest resolvedFrom(JsonNode intent) {
        return new ExecutionRequest(workflowId, input, organization, traceParent, intent, principal);
    }

    public Optional<JsonNode> intentIfAny() {
        return Optional.ofNullable(intent);
    }

    public Optional<String> traceParentIfAny() {
        return Optional.ofNullable(traceParent).filter(value -> !value.isBlank());
    }
}
