package io.pipemesh.core.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.Objects;

/**
 * One node of a workflow definition.
 *
 * <p>The engine understands only {@code id} and {@code type}. Everything else
 * stays in {@code config}, interpreted by the executor that claims the type —
 * which is what keeps a new step type from reaching into core.
 */
public record Step(StepId id, StepType type, JsonNode config) {

    public Step {
        Objects.requireNonNull(id, "step id");
        Objects.requireNonNull(type, "step type");
        config = config == null ? NullNode.getInstance() : config.deepCopy();
    }

    @Override
    public JsonNode config() {
        return config.deepCopy();
    }
}
