package io.pipemesh.core.execution;

import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepType;

/**
 * The extension point of the engine (§27).
 *
 * <p>Adding a primitive means adding an implementation of this interface and a
 * schema entry — nothing in the executor or the scheduler changes. If a new step
 * type ever forces an edit to core, the abstraction is leaking (§46).
 */
public interface StepExecutor {

    boolean supports(StepType type);

    StepResult execute(Step step, ExecutionContext context);
}
