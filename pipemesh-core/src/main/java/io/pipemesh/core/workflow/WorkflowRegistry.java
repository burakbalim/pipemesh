package io.pipemesh.core.workflow;

import java.util.Optional;

/**
 * Resolves a workflow id to a compiled graph. Compilation happens on
 * registration, not on every run (§25).
 */
public interface WorkflowRegistry {

    Optional<ExecutionGraph> find(WorkflowId workflowId);
}
