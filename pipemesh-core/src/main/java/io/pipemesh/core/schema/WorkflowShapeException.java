package io.pipemesh.core.schema;

import java.util.List;
import java.util.stream.Collectors;

/** A workflow whose shape the format does not allow. */
public class WorkflowShapeException extends RuntimeException {

    private final List<SchemaViolation> violations;

    public WorkflowShapeException(String workflowId, List<SchemaViolation> violations) {
        super("workflow " + workflowId + " has a shape this format does not allow: "
                + violations.stream().map(SchemaViolation::toString).collect(Collectors.joining("; ")));
        this.violations = List.copyOf(violations);
    }

    public List<SchemaViolation> violations() {
        return violations;
    }
}
