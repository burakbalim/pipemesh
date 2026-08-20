package io.pipemesh.core.policy;

import com.fasterxml.jackson.databind.JsonNode;
import io.pipemesh.core.workflow.Step;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The retry, timeout and failure rules in force for one step.
 *
 * <p>Resolved narrowest-first: what the step says beats what the workflow says,
 * which beats the runtime default (§17). A step that says nothing behaves exactly
 * as it did before policies existed — one attempt, no deadline, fail on failure.
 */
public record StepPolicy(RetryPolicy retry, Duration timeout, FailurePolicy onFailure) {

    public static final StepPolicy DEFAULT = new StepPolicy(RetryPolicy.NONE, null, FailurePolicy.FAIL);

    private static final String RETRY = "retry";
    private static final String TIMEOUT = "timeout";
    private static final String ON_FAILURE = "onFailure";

    public StepPolicy {
        Objects.requireNonNull(retry, "retry policy");
        Objects.requireNonNull(onFailure, "failure policy");
    }

    public static StepPolicy from(JsonNode config) {
        if (config == null || !config.isObject()) {
            return DEFAULT;
        }
        return new StepPolicy(
                RetryPolicy.from(config.get(RETRY)),
                config.has(TIMEOUT) ? DurationText.parse(config.path(TIMEOUT).asText(""), null) : null,
                FailurePolicy.from(config.get(ON_FAILURE)));
    }

    /** The step's own settings, falling back to {@code inherited} field by field. */
    public static StepPolicy resolve(Step step, StepPolicy inherited) {
        JsonNode config = step.config();
        StepPolicy own = from(config);

        return new StepPolicy(
                config.has(RETRY) ? own.retry() : inherited.retry(),
                config.has(TIMEOUT) ? own.timeout() : inherited.timeout(),
                config.has(ON_FAILURE) ? own.onFailure() : inherited.onFailure());
    }

    public Optional<Duration> timeoutIfAny() {
        return Optional.ofNullable(timeout);
    }
}
