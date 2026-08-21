package io.pipemesh.core.schema;

import com.fasterxml.jackson.databind.JsonNode;
import io.pipemesh.core.execution.StepExecutor;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.workflow.StepType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Checks a workflow's shape before it becomes a workflow.
 *
 * <p>At load time rather than at run time, on purpose: a definition with a field
 * nobody understands should never be registered, let alone discovered halfway
 * through an execution when a step reads something that was never there.
 *
 * <p>What it refuses is as important as what it accepts. A step of a known type
 * carrying an unknown field — {@code code}, {@code script}, anything — is a
 * violation, because that is the door §23.1 exists to keep shut.
 */
public final class WorkflowValidator {

    private final JsonSchemaValidator validator = new JsonSchemaValidator();
    private final JsonNode workflowShape = Schemas.load("workflow.schema.json");
    private final JsonNode stepCommon = Schemas.load("step-common.schema.json");
    private final StepExecutors executors;

    public WorkflowValidator(StepExecutors executors) {
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    /**
     * @throws WorkflowShapeException listing everything wrong, not just the first
     *                                thing — fixing a workflow one field per
     *                                attempt is a poor way to spend a morning
     */
    public void validate(JsonNode workflow) {
        List<SchemaViolation> violations = new ArrayList<>(validator.validate(workflowShape, workflow));

        int index = 0;
        for (JsonNode step : workflow.path("steps")) {
            violations.addAll(validateStep(step, index++));
        }

        if (!violations.isEmpty()) {
            throw new WorkflowShapeException(workflow.path("id").asText("workflow"), violations);
        }
    }

    private List<SchemaViolation> validateStep(JsonNode step, int index) {
        String type = step.path("type").asText("");
        Optional<StepExecutor> executor = executors.find(StepType.of(type.isBlank() ? "?" : type));

        // An unclaimed type is the compiler's to report, and it says so in words
        // about the type rather than about JSON.
        if (executor.isEmpty()) {
            return List.of();
        }

        Optional<JsonNode> declared = executor.get().configSchema();
        if (declared.isEmpty()) {
            return List.of();
        }

        return validator.validate(Schemas.merge(stepCommon, declared.get()), step).stream()
                .map(violation -> new SchemaViolation(
                        "steps[" + index + "]" + violation.path().substring(1), violation.message()))
                .toList();
    }
}
