package io.pipemesh.core.execution;

import java.util.Objects;
import java.util.Optional;

/**
 * A message for the runtime to read, and what to run it with.
 *
 * <p>Deliberately not an {@link ExecutionRequest} with the workflow left blank:
 * the two are different asks. One says what to run, the other asks what should be
 * run — and a type that blurred them would make it easy to write code that does
 * not know which it is doing.
 */
public record ProcessRequest(
        String message,
        ExecutionInput input,
        OrganizationId organization,
        String traceParent) {

    public ProcessRequest {
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        input = input == null ? ExecutionInput.empty() : input;
        organization = organization == null ? OrganizationId.DEFAULT : organization;
    }

    public static ProcessRequest of(String message) {
        return new ProcessRequest(message, ExecutionInput.empty(), OrganizationId.DEFAULT, null);
    }

    public static ProcessRequest of(String message, ExecutionInput input, OrganizationId organization) {
        return new ProcessRequest(message, input, organization, null);
    }

    public Optional<String> traceParentIfAny() {
        return Optional.ofNullable(traceParent).filter(value -> !value.isBlank());
    }
}
