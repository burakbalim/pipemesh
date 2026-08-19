package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The payload a workflow starts from. JSON, so it crosses the boundary unchanged. */
public record ExecutionInput(ObjectNode value) {

    public ExecutionInput {
        value = value == null ? JsonNodeFactory.instance.objectNode() : value.deepCopy();
    }

    public static ExecutionInput empty() {
        return new ExecutionInput(null);
    }

    @Override
    public ObjectNode value() {
        return value.deepCopy();
    }
}
