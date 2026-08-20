package io.pipemesh.core.intent;

import io.pipemesh.core.workflow.WorkflowId;

import java.util.List;
import java.util.Objects;

/**
 * One thing a user might be asking for, and the workflow that handles it.
 *
 * <p>{@code matches} is the deterministic half: phrases that settle the question
 * without asking a model. {@code description} is the half a model reads when the
 * phrases do not settle it (§20).
 */
public record IntentDefinition(
        IntentId id,
        WorkflowId workflow,
        String description,
        List<String> matches) {

    public IntentDefinition {
        Objects.requireNonNull(id, "intent id");
        Objects.requireNonNull(workflow, "workflow");
        description = description == null ? "" : description;
        matches = List.copyOf(matches == null ? List.of() : matches);
    }
}
