package io.pipemesh.core.execution;

import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

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

    /**
     * Whether this step can be run again when nobody knows how far the last run
     * got.
     *
     * <p>Asked during recovery, not during retry: a retry follows a failure that
     * was reported, while recovery follows a silence. A step that may already have
     * had an effect must say no, and only the code that reads its config can tell.
     *
     * <p>The context comes with the question because the answer can depend on the
     * execution: a step that runs other steps has to ask them, and it cannot find
     * them without knowing which workflow this is.
     */
    default boolean repeatable(Step step, ExecutionContext context) {
        return true;
    }

    /**
     * The fields this step type understands, as a JSON Schema fragment.
     *
     * <p>Only what is its own: the fields every step may carry — {@code id},
     * {@code retry}, {@code timeout}, {@code onFailure} — are merged in by the
     * validator, so a step type declares its own vocabulary and nothing else.
     *
     * <p>Declaring one closes the shape: a field nobody named is refused, which is
     * how "a workflow never carries executable code" becomes something the format
     * enforces rather than something the engine happens not to run (§23.1).
     *
     * <p>Empty means no constraint. A step type that declares nothing keeps
     * working — a third party adding one should not have to write a schema before
     * anything runs at all.
     */
    default Optional<JsonNode> configSchema() {
        return Optional.empty();
    }
}
