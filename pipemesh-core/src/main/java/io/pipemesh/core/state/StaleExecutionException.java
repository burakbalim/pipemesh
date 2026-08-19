package io.pipemesh.core.state;

import io.pipemesh.core.execution.ExecutionId;

/**
 * Thrown when a write loses the optimistic-locking race — another worker has
 * already advanced this execution.
 */
public class StaleExecutionException extends RuntimeException {

    private final ExecutionId executionId;

    public StaleExecutionException(ExecutionId executionId, long expectedVersion) {
        super("execution " + executionId + " was modified concurrently (expected version "
                + expectedVersion + ")");
        this.executionId = executionId;
    }

    public ExecutionId executionId() {
        return executionId;
    }
}
