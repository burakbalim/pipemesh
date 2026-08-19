package io.pipemesh.core.execution;

/**
 * Lifecycle of an execution (§15). Closed by design: these are the states the
 * engine itself reasons about, and persistence and the wire format both depend
 * on the set being stable.
 */
public enum ExecutionStatus {

    CREATED,
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public boolean isResumable() {
        return this == WAITING;
    }
}
