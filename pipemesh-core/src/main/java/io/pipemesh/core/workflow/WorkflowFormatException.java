package io.pipemesh.core.workflow;

/** Malformed JSON, or a workflow missing a field the format requires. */
public class WorkflowFormatException extends RuntimeException {

    public WorkflowFormatException(String message) {
        super(message);
    }
}
