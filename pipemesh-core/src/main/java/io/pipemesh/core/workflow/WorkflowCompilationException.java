package io.pipemesh.core.workflow;

import java.util.List;

/**
 * A definition that cannot become a graph. Carries every problem found, not just
 * the first: fixing a workflow one error per compile is a poor way to spend a day.
 */
public class WorkflowCompilationException extends RuntimeException {

    private final List<String> problems;

    public WorkflowCompilationException(WorkflowId workflowId, List<String> problems) {
        super("workflow " + workflowId + " is invalid: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
