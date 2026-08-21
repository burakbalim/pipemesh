package io.pipemesh.core.execution.step;

import com.fasterxml.jackson.databind.JsonNode;
import io.pipemesh.core.execution.ExecutionContext;
import io.pipemesh.core.execution.StepExecutor;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.StepResult;
import io.pipemesh.core.workflow.ExecutionGraph;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Walks one branch of a parallel step, from its entry to the join.
 *
 * <p>Deliberately not the engine. It persists nothing, suspends nothing and ends
 * no execution — a branch is work done inside a single step, and everything the
 * engine guarantees about steps still applies to that step as a whole (§9.5).
 *
 * <p>What it collects is what the branch <em>wrote</em>, not the context it
 * finished with. Two branches that both started from the same variables would
 * otherwise each hand back a full copy, and merging those would quietly overwrite
 * whichever finished first.
 */
final class BranchRun {

    /** A branch that walks further than this is looping, not branching. */
    private static final int STEP_BUDGET = 100;

    private final ExecutionGraph graph;
    private final StepExecutors executors;
    private final StepId join;

    BranchRun(ExecutionGraph graph, StepExecutors executors, StepId join) {
        this.graph = graph;
        this.executors = executors;
        this.join = join;
    }

    /** What a branch left behind, or why it could not finish. */
    record Outcome(
            String branch,
            Map<String, JsonNode> writes,
            int stepsRun,
            Optional<StepResult.Failed> failure) {

        static Outcome failed(String branch, int stepsRun, String code, String message) {
            return new Outcome(branch, Map.of(), stepsRun,
                    Optional.of(new StepResult.Failed(code, message, false)));
        }
    }

    Outcome walk(StepId entry, ExecutionContext incoming) {
        Map<String, JsonNode> writes = new LinkedHashMap<>();
        ExecutionContext context = incoming.at(entry);
        StepId at = entry;
        int stepsRun = 0;

        while (stepsRun < STEP_BUDGET) {
            if (at.equals(join)) {
                return new Outcome(entry.value(), Map.copyOf(writes), stepsRun, Optional.empty());
            }

            Optional<Step> step = graph.step(at);
            if (step.isEmpty()) {
                return Outcome.failed(entry.value(), stepsRun, "parallel.branch_escaped",
                        "branch '" + entry + "' reached '" + at + "', which is not in this workflow");
            }

            StepExecutor executor = executors.forType(step.get().type());
            StepResult result = executor.execute(step.get(), context);
            stepsRun++;

            switch (result) {
                case StepResult.Continue moved -> {
                    writes.putAll(moved.variables());
                    context = context.with(moved.variables()).at(moved.nextStep());
                    at = moved.nextStep();
                }
                case StepResult.Failed failure -> {
                    return new Outcome(entry.value(), Map.copyOf(writes), stepsRun,
                            Optional.of(failure));
                }
                case StepResult.Suspend ignored -> {
                    // Suspending moves an execution's position, and a branch has no
                    // position of its own to move.
                    return Outcome.failed(entry.value(), stepsRun, "parallel.branch_suspended",
                            "branch '" + entry + "' stopped at '" + at + "' to wait for something,"
                                    + " which a branch cannot do");
                }
                case StepResult.Terminate ignored -> {
                    // Ending the execution from inside one branch would settle it
                    // while its siblings were still working.
                    return Outcome.failed(entry.value(), stepsRun, "parallel.branch_escaped",
                            "branch '" + entry + "' ended the execution at '" + at
                                    + "' instead of reaching the join");
                }
            }
        }

        return Outcome.failed(entry.value(), stepsRun, "parallel.branch_runaway",
                "branch '" + entry + "' took " + STEP_BUDGET + " steps without reaching the join");
    }

    /** Whether every step this branch can walk through may be run a second time. */
    boolean repeatable(StepId entry, ExecutionContext context) {
        StepId at = entry;
        for (int visited = 0; visited < STEP_BUDGET && !at.equals(join); visited++) {
            Optional<Step> step = graph.step(at);
            if (step.isEmpty()) {
                return true;
            }
            StepExecutor executor = executors.find(step.get().type()).orElse(null);
            if (executor == null) {
                return true;
            }
            if (!executor.repeatable(step.get(), context)) {
                return false;
            }
            java.util.List<StepId> next = executor.outgoing(step.get());
            if (next.isEmpty()) {
                return true;
            }
            at = next.get(0);
        }
        return true;
    }
}
