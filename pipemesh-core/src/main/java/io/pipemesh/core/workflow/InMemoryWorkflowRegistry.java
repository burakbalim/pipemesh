package io.pipemesh.core.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import io.pipemesh.core.schema.WorkflowValidator;

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
    private final WorkflowValidator shape;
    private final Map<WorkflowId, ExecutionGraph> graphs = new ConcurrentHashMap<>();

    public InMemoryWorkflowRegistry(WorkflowCompiler compiler) {
        this(compiler, null);
    }

    /**
     * @param shape checks the definition's fields before it is compiled, or
     *              {@code null} to accept any shape the reader could parse
     */
    public InMemoryWorkflowRegistry(WorkflowCompiler compiler, WorkflowValidator shape) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.shape = shape;
    }

    public ExecutionGraph register(WorkflowDefinition definition) {
        ExecutionGraph graph = compiler.compile(definition);
        graphs.put(definition.id(), graph);
        return graph;
    }

    /**
     * Registers a workflow from its source, checking the shape first.
     *
     * <p>The source is needed because a {@link WorkflowDefinition} has already
     * dropped whatever it did not understand — and what it did not understand is
     * exactly what this refuses (§23.1).
     */
    public ExecutionGraph register(JsonNode source) {
        if (shape != null) {
            shape.validate(source);
        }
        return register(new WorkflowDefinitionReader().read(source));
    }

    @Override
    public Optional<ExecutionGraph> find(WorkflowId workflowId) {
        return Optional.ofNullable(graphs.get(workflowId));
    }
}
