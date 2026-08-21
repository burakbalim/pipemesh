package io.pipemesh.core.execution.step;

import com.fasterxml.jackson.databind.JsonNode;

import io.pipemesh.core.execution.ExecutionContext;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.StepExecutor;
import io.pipemesh.core.execution.StepResult;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepType;

import java.util.Optional;

/**
 * Ends an execution in a declared status.
 *
 * <pre>
 * { "type": "terminal", "status": "COMPLETED" }
 * </pre>
 *
 * <p>Terminal states are explicit nodes rather than an implicit "ran out of
 * steps", so that a definition says how each path ends — {@code COMPLETED} and
 * {@code CANCELLED} are different outcomes and the graph should show which is
 * which.
 */
public final class TerminalStepExecutor implements StepExecutor {

    private static final JsonNode SCHEMA = StepSchemas.parse("""
            {
              "properties": {
                "status": {"type": "string", "enum": ["COMPLETED", "CANCELLED", "FAILED"]}
              }
            }
            """);

    private static final String STATUS = "status";

    @Override
    public boolean supports(StepType type) {
        return StepType.TERMINAL.equals(type);
    }


    /** What a terminal step may say. Anything else is refused at load time (§23.1). */
    @Override
    public Optional<JsonNode> configSchema() {
        return Optional.of(SCHEMA);
    }

    @Override
    public StepResult execute(Step step, ExecutionContext context) {
        String declared = step.config().path(STATUS).asText(ExecutionStatus.COMPLETED.name());
        ExecutionStatus status = parse(declared);
        if (status == null || !status.isTerminal()) {
            return new StepResult.Failed("terminal.invalid_status",
                    "step " + step.id() + " declares status '" + declared + "'", false);
        }
        return new StepResult.Terminate(status);
    }

    private ExecutionStatus parse(String declared) {
        try {
            return ExecutionStatus.valueOf(declared.toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
