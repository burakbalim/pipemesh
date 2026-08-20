package io.pipemesh.core.policy;

import com.fasterxml.jackson.databind.JsonNode;
import io.pipemesh.core.workflow.StepId;

import java.util.Objects;
import java.util.Optional;

/**
 * What happens once retrying is done and the step still failed (§18).
 *
 * <p>The runtime decides this, not the model: a workflow that asks an LLM what to
 * do about a timeout has handed control of its failure semantics to the thing
 * that just failed.
 */
public record FailurePolicy(Strategy strategy, String fallbackModel, String fallbackPrompt, StepId target) {

    /** End the execution — what a step gets when nothing is configured. */
    public static final FailurePolicy FAIL = new FailurePolicy(Strategy.FAIL, null, null, null);

    public enum Strategy {
        /** Give up; the execution fails. */
        FAIL,

        /** Run the step once more against a different model. */
        FALLBACK,

        /** Record the failure in a variable and carry on down {@code next}. */
        CONTINUE,

        /** Branch to a named step — a clarification path, a compensation step. */
        GOTO
    }

    public FailurePolicy {
        Objects.requireNonNull(strategy, "strategy");
    }

    public static FailurePolicy from(JsonNode config) {
        if (config == null || !config.isObject()) {
            return FAIL;
        }
        Strategy strategy = strategyOf(config.path("strategy").asText("fail"));
        String target = config.path("goto").asText("");

        return new FailurePolicy(
                strategy,
                emptyToNull(config.path("model").asText("")),
                emptyToNull(config.path("prompt").asText("")),
                target.isBlank() ? null : StepId.of(target));
    }

    /**
     * The fallback may name its own prompt. Sending a prompt written for a
     * reasoning model to a small local one produces a worse answer silently,
     * which is harder to notice than an outright failure.
     */
    public Optional<String> fallbackPromptIfAny() {
        return Optional.ofNullable(fallbackPrompt);
    }

    public Optional<String> fallbackModelIfAny() {
        return Optional.ofNullable(fallbackModel);
    }

    public Optional<StepId> targetIfAny() {
        return Optional.ofNullable(target);
    }

    private static Strategy strategyOf(String name) {
        try {
            return Strategy.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("unknown onFailure strategy: '" + name + "'");
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
