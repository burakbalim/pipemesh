package io.pipemesh.core.intent;

import io.pipemesh.core.workflow.WorkflowId;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of reading a message: a workflow, and how that was decided.
 *
 * <p>How it was decided travels with it because the answer to "why did this
 * execution run?" should not require guessing. A phrase match and a model's
 * judgement are different kinds of answer and an operator should be able to tell
 * which one they are looking at.
 */
public record ResolvedIntent(
        IntentId intent,
        WorkflowId workflow,
        Source source,
        double confidence,
        String model,
        String promptVersion) {

    public enum Source {
        /** A registered phrase settled it, without asking anything. */
        DETERMINISTIC,

        /** A model read the message and chose. */
        MODEL
    }

    public ResolvedIntent {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(source, "source");
        model = model == null ? "" : model;
        promptVersion = promptVersion == null ? "" : promptVersion;
    }

    public static ResolvedIntent byPhrase(IntentDefinition intent) {
        return new ResolvedIntent(intent.id(), intent.workflow(), Source.DETERMINISTIC, 1.0, "", "");
    }

    public Optional<String> modelIfAny() {
        return model.isBlank() ? Optional.empty() : Optional.of(model);
    }
}
