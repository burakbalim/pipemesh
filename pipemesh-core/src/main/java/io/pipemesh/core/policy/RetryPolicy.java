package io.pipemesh.core.policy;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;

/**
 * When to try a failed step again (§17).
 *
 * <p>Retrying is an execution policy, not something a workflow should express as
 * business logic — a step that loops back to itself to "try again" has put an
 * operational concern into the graph where nobody can change it without editing
 * the workflow.
 */
public record RetryPolicy(int maxAttempts, Backoff backoff, Duration initialDelay, Duration maxDelay) {

    /** One attempt, no waiting — what a step gets when nothing is configured. */
    public static final RetryPolicy NONE =
            new RetryPolicy(1, Backoff.FIXED, Duration.ZERO, Duration.ZERO);

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        backoff = backoff == null ? Backoff.EXPONENTIAL : backoff;
        initialDelay = initialDelay == null ? Duration.ZERO : initialDelay;
        maxDelay = maxDelay == null ? Duration.ofSeconds(30) : maxDelay;
    }

    public static RetryPolicy from(JsonNode config) {
        if (config == null || !config.isObject()) {
            return NONE;
        }
        return new RetryPolicy(
                config.path("maxAttempts").asInt(1),
                Backoff.of(config.path("backoff").asText("exponential")),
                DurationText.parse(config.path("initialDelay").asText(""), Duration.ZERO),
                DurationText.parse(config.path("maxDelay").asText(""), Duration.ofSeconds(30)));
    }

    public boolean retries() {
        return maxAttempts > 1;
    }

    public Duration delayBefore(int attempt) {
        return backoff.delayBefore(attempt, initialDelay, maxDelay);
    }
}
