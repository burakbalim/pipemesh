package io.pipemesh.core.workflow;

import java.util.Optional;

/**
 * Resolves a workflow to a compiled graph. Compilation happens on registration,
 * not on every run (§25).
 *
 * <p>There is no lookup by id alone, and that is the point (§24). Only one caller
 * — a request to start something new — is entitled to ask for "whatever is
 * current"; everything else is continuing an execution that already chose, and
 * asking again would let a deploy move it to a different graph mid-flight.
 */
public interface WorkflowRegistry {

    /** The graph an execution started on, resolved from its own record. */
    Optional<ExecutionGraph> find(WorkflowId workflowId, WorkflowVersion version);

    /** The newest registered version, for a run that has not chosen one yet. */
    Optional<ExecutionGraph> latest(WorkflowId workflowId);
}
