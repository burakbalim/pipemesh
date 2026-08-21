package io.pipemesh.core.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import io.pipemesh.core.schema.WorkflowValidator;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds compiled workflows in memory. A definition is rejected at registration
 * time if it does not compile, so a broken workflow is never reachable by a run.
 *
 * <p>Registering a new version does not displace the old one: an execution that
 * suspended yesterday still has to find the graph it stopped inside (§24).
 */
public final class InMemoryWorkflowRegistry implements WorkflowRegistry {

    /** Identity is the pair, so {@code 1.0} and {@code 1.0.0} are two entries. */
    private record Key(WorkflowId id, WorkflowVersion version) {
    }

    private final WorkflowCompiler compiler;
    private final WorkflowValidator shape;
    private final Map<Key, WorkflowDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<Key, ExecutionGraph> graphs = new ConcurrentHashMap<>();

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

    /**
     * Registering the same version twice is fine when the definition is the same
     * — restarting a process and reading the same directory must not be an error.
     * With a different definition it is refused: a version that can mean two
     * things is a label, not an identity, and "it ran on 1.2" would say nothing.
     */
    public ExecutionGraph register(WorkflowDefinition definition) {
        Key key = new Key(definition.id(), definition.version());
        WorkflowDefinition known = definitions.get(key);
        if (known != null) {
            return sameOrRefused(key, known, definition);
        }

        ExecutionGraph graph = compiler.compile(definition);
        definitions.put(key, definition);
        graphs.put(key, graph);
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
    public Optional<ExecutionGraph> find(WorkflowId workflowId, WorkflowVersion version) {
        return Optional.ofNullable(graphs.get(new Key(workflowId, version)));
    }

    @Override
    public Optional<ExecutionGraph> latest(WorkflowId workflowId) {
        return graphs.keySet().stream()
                .filter(key -> key.id().equals(workflowId))
                .max(Comparator.comparing(Key::version))
                .map(graphs::get);
    }

    private ExecutionGraph sameOrRefused(
            Key key, WorkflowDefinition known, WorkflowDefinition arriving) {

        if (!known.equals(arriving)) {
            throw new IllegalStateException(
                    "workflow " + key.id() + "@" + key.version()
                            + " is already registered with a different definition;"
                            + " publish a new version rather than changing this one");
        }
        return graphs.get(key);
    }
}
