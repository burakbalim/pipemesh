package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.JsonNode;
import io.pipemesh.core.workflow.StepId;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome vocabulary of a step. Closed on purpose: the engine has to handle
 * every case, so a new variant is a change to the engine by definition.
 */
public sealed interface StepResult {

    /** Move on, writing {@code variables} into the execution context. */
    record Continue(StepId nextStep, Map<String, JsonNode> variables) implements StepResult {

        public Continue {
            Objects.requireNonNull(nextStep, "next step");
            variables = Map.copyOf(variables == null ? Map.of() : variables);
        }

        public static Continue to(StepId nextStep) {
            return new Continue(nextStep, Map.of());
        }
    }

    /**
     * Stop and persist. The execution releases its resources and waits for an
     * external signal — no thread is held (§16).
     */
    record Suspend(SuspensionReason reason, Duration timeout) implements StepResult {

        public Suspend {
            Objects.requireNonNull(reason, "suspension reason");
        }

        public Optional<Duration> timeoutIfAny() {
            return Optional.ofNullable(timeout);
        }
    }

    /** Finish the execution in a terminal status. */
    record Terminate(ExecutionStatus status) implements StepResult {

        public Terminate {
            Objects.requireNonNull(status, "status");
            if (!status.isTerminal()) {
                throw new IllegalArgumentException("not a terminal status: " + status);
            }
        }
    }

    /** The step did not produce a result. {@code retryable} is advice to the policy layer. */
    record Failed(String code, String message, boolean retryable) implements StepResult {

        public Failed {
            Objects.requireNonNull(code, "failure code");
            message = message == null ? "" : message;
        }
    }
}
