package io.pipemesh.core.execution;

import io.pipemesh.core.workflow.Step;

/**
 * A step that can be entered twice: once when the execution reaches it, and again
 * when the signal it was waiting for arrives.
 *
 * <p>Deciding what a signal means — which branch an approval takes, whether an
 * event matches — stays with the executor that wrote the suspension. The engine
 * only knows that something arrived and that this step knows what to do with it.
 */
public interface ResumableStepExecutor extends StepExecutor {

    StepResult resume(Step step, ExecutionContext context, ResumeSignal signal);
}
