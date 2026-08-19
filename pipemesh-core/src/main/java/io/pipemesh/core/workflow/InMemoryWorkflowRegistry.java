package io.pipemesh.core.workflow;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds compiled workflows in memory. A definition is rejected at registration
 * time if it does not compile, so a broken workflow is never reachable by a run.
 */
public final class InMemoryWorkflowRegistry implements WorkflowRegistry {

    private final WorkflowCompiler compiler;
    private final Map<WorkflowId, ExecutionGraph> graphs = new ConcurrentHashMap<>();

    public InMemoryWorkflowRegistry(WorkflowCompiler compiler) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    public ExecutionGraph register(WorkflowDefinition definition) {
        ExecutionGraph graph = compiler.compile(definition);
        graphs.put(definition.id(), graph);
        return graph;
    }

    @Override
    public Optional<ExecutionGraph> find(WorkflowId workflowId) {
        return Optional.ofNullable(graphs.get(workflowId));
    }
}
