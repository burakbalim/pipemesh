package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.WorkflowId;
import io.pipemesh.core.workflow.WorkflowVersion;

import java.util.Map;
import java.util.Objects;

/**
 * What a step is given and what it leaves behind (§14).
 *
 * <p>Immutable: advancing an execution produces a new context rather than
 * mutating one. Variables are JSON so the whole context can cross the gRPC
 * boundary unchanged (§26.1).
 */
public record ExecutionContext(
        ExecutionId executionId,
        WorkflowId workflowId,
        WorkflowVersion workflowVersion,
        StepId currentStep,
        ObjectNode variables) {

    public ExecutionContext {
        Objects.requireNonNull(executionId, "execution id");
        Objects.requireNonNull(workflowId, "workflow id");
        Objects.requireNonNull(workflowVersion, "workflow version");
        Objects.requireNonNull(currentStep, "current step");
        variables = variables == null ? JsonNodeFactory.instance.objectNode() : variables.deepCopy();
    }

    @Override
    public ObjectNode variables() {
        return variables.deepCopy();
    }

    public JsonNode variable(String name) {
        return variables.path(name);
    }

    public ExecutionContext at(StepId step) {
        return new ExecutionContext(executionId, workflowId, workflowVersion, step, variables);
    }

    /** Returns a context with {@code additions} merged over the current variables. */
    public ExecutionContext with(Map<String, JsonNode> additions) {
        ObjectNode merged = variables.deepCopy();
        additions.forEach(merged::set);
        return new ExecutionContext(executionId, workflowId, workflowVersion, currentStep, merged);
    }
}
