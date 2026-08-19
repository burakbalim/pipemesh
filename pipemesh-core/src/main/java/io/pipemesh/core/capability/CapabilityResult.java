package io.pipemesh.core.capability;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** What an invocation produced. Closed: the engine handles both cases. */
public sealed interface CapabilityResult {

    record Success(JsonNode output) implements CapabilityResult {

        public Success {
            Objects.requireNonNull(output, "output");
            output = output.deepCopy();
        }

        @Override
        public JsonNode output() {
            return output.deepCopy();
        }
    }

    record Failure(String code, String message, boolean retryable) implements CapabilityResult {

        public Failure {
            Objects.requireNonNull(code, "failure code");
            message = message == null ? "" : message;
        }
    }
}
