package io.pipemesh.core.execution;

import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;

import java.util.List;

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

    /**
     * The steps this one can hand control to, as declared in its config.
     *
     * <p>The compiler needs the graph's edges, but it must not learn that a
     * condition uses {@code onTrue} while an approval uses {@code onApproved} —
     * that knowledge belongs to whoever interprets the config. Asking the
     * executor keeps edge validation working for step types core has never
     * heard of.
     */
    default List<StepId> outgoing(Step step) {
        return List.of();
    }
}
