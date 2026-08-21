package io.pipemesh.core.capability;

import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.workflow.StepId;

import java.util.Objects;
import java.util.Optional;

/**
 * Who is asking, and on whose behalf.
 *
 * <p>A provider that reaches a local tool does not need any of this. One that
 * routes to a remote worker needs all of it: which organization's workers may
 * serve the call, which execution to correlate the answer with, and the trace to
 * hang the worker's own spans from.
 *
 * <p>Separate from the input on purpose — this is about the call, not about what
 * the capability was asked to do.
 */
public record CapabilityCall(
        OrganizationId organization,
        ExecutionId executionId,
        StepId stepId,
        String traceParent,
        Principal principal) {

    public CapabilityCall(
            OrganizationId organization, ExecutionId executionId, StepId stepId, String traceParent) {

        this(organization, executionId, stepId, traceParent, Principal.SYSTEM);
    }

    public CapabilityCall {
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(executionId, "execution id");
        Objects.requireNonNull(stepId, "step id");
        traceParent = traceParent == null ? "" : traceParent;
        principal = principal == null ? Principal.SYSTEM : principal;
    }

    public Optional<String> traceParentIfAny() {
        return traceParent.isBlank() ? Optional.empty() : Optional.of(traceParent);
    }
}
