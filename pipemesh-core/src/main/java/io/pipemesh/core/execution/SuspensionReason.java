package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.Objects;

/**
 * Why an execution stopped without finishing. Open-ended like {@link
 * io.pipemesh.core.workflow.StepType}: a step type that introduces a new kind of
 * wait must not require a change here.
 */
public record SuspensionReason(String kind, JsonNode detail) {

    public static final String APPROVAL = "approval";

    public SuspensionReason {
        Objects.requireNonNull(kind, "suspension kind");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("suspension kind must not be blank");
        }
        detail = detail == null ? NullNode.getInstance() : detail.deepCopy();
    }

    @Override
    public JsonNode detail() {
        return detail.deepCopy();
    }
}
