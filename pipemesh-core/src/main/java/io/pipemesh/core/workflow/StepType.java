package io.pipemesh.core.workflow;

import java.util.Objects;

/**
 * Type of a workflow step.
 *
 * <p>Deliberately not an enum. A new step type must arrive as a new
 * {@code StepExecutor} plus a schema entry, without editing anything in the
 * engine — an enum here would make core a place every extension has to touch
 * (§27, §46).
 */
public record StepType(String name) {

    public static final StepType LLM = new StepType("llm");
    public static final StepType CAPABILITY = new StepType("capability");
    public static final StepType CONDITION = new StepType("condition");
    public static final StepType HUMAN_APPROVAL = new StepType("human_approval");
    public static final StepType TERMINAL = new StepType("terminal");

    public StepType {
        Objects.requireNonNull(name, "step type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("step type must not be blank");
        }
    }

    public static StepType of(String name) {
        return new StepType(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
