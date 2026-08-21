package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.JsonNode;
import io.pipemesh.core.capability.Principal;
import io.pipemesh.core.workflow.WorkflowId;
import io.pipemesh.core.workflow.WorkflowVersion;

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
 *
 * <p>{@code workflowVersion} is optional and means "pin this run to exactly that
 * version". Left out, the newest registered version is chosen — once, at the
 * start — and written to the record, so the rest of the run has an answer rather
 * than a preference (§24).
 */
public record ExecutionRequest(
        WorkflowId workflowId,
        ExecutionInput input,
        OrganizationId organization,
        String traceParent,
        JsonNode intent,
        Principal principal,
        WorkflowVersion workflowVersion) {

    public ExecutionRequest(
            WorkflowId workflowId, ExecutionInput input,
            OrganizationId organization, String traceParent) {

        this(workflowId, input, organization, traceParent, null, Principal.SYSTEM, null);
    }

    public ExecutionRequest(
            WorkflowId workflowId, ExecutionInput input,
            OrganizationId organization, String traceParent, JsonNode intent) {

        this(workflowId, input, organization, traceParent, intent, Principal.SYSTEM, null);
    }

    public ExecutionRequest(
            WorkflowId workflowId, ExecutionInput input, OrganizationId organization,
            String traceParent, JsonNode intent, Principal principal) {

        this(workflowId, input, organization, traceParent, intent, principal, null);
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
        return new ExecutionRequest(
                workflowId, input, organization, traceParent, intent, principal, workflowVersion);
    }

    /** Runs this on one named version rather than whatever is newest. */
    public ExecutionRequest pinnedTo(WorkflowVersion workflowVersion) {
        return new ExecutionRequest(
                workflowId, input, organization, traceParent, intent, principal, workflowVersion);
    }

    public Optional<WorkflowVersion> versionIfPinned() {
        return Optional.ofNullable(workflowVersion);
    }

    /**
     * Runs this on behalf of somebody in particular.
     *
     * <p>Set by whoever authenticated them. A request that arrived over a network
     * never carries its own answer to this (§23).
     */
    public ExecutionRequest onBehalfOf(Principal principal) {
        return new ExecutionRequest(
                workflowId, input, organization, traceParent, intent, principal, workflowVersion);
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
