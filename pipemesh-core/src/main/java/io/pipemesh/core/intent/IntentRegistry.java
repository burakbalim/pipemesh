package io.pipemesh.core.intent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The intents a runtime knows about, and how confident a model must be before one
 * of them is acted on.
 */
public record IntentRegistry(
        List<IntentDefinition> intents,
        String model,
        String prompt,
        double minimumConfidence) {

    public static final double DEFAULT_MINIMUM_CONFIDENCE = 0.6;

    public IntentRegistry {
        intents = List.copyOf(Objects.requireNonNull(intents, "intents"));
        model = model == null ? "" : model;
        prompt = prompt == null ? "" : prompt;
        if (minimumConfidence < 0 || minimumConfidence > 1) {
            throw new IllegalArgumentException("minimum confidence must be between 0 and 1");
        }
    }

    public static IntentRegistry of(List<IntentDefinition> intents) {
        return new IntentRegistry(intents, "", "", DEFAULT_MINIMUM_CONFIDENCE);
    }

    public Optional<IntentDefinition> find(IntentId id) {
        return intents.stream().filter(intent -> intent.id().equals(id)).findFirst();
    }

    /** Whether a model can be asked at all — an unconfigured one is not an error, just a limit. */
    public boolean canAskAModel() {
        return !model.isBlank() && !prompt.isBlank();
    }

    public boolean isEmpty() {
        return intents.isEmpty();
    }
}
