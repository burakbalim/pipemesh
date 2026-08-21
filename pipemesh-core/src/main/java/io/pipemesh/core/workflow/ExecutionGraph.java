package io.pipemesh.core.workflow;

import io.pipemesh.core.cost.CostBudget;
import io.pipemesh.core.policy.StepPolicy;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The compiled, validated form of a workflow (§7).
 *
 * <p>The runtime executes this, never the raw JSON: a definition is parsed and
 * checked once, then run many times.
 */
public record ExecutionGraph(
        WorkflowId workflowId,
        WorkflowVersion version,
        StepId entry,
        Map<StepId, Step> steps,
        StepPolicy defaults,
        CostBudget budget) {

    public ExecutionGraph(
            WorkflowId workflowId, WorkflowVersion version, StepId entry,
            Map<StepId, Step> steps, StepPolicy defaults) {

        this(workflowId, version, entry, steps, defaults, CostBudget.UNLIMITED);
    }

    public ExecutionGraph {
        Objects.requireNonNull(workflowId, "workflow id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(entry, "entry");
        steps = Map.copyOf(steps);
        defaults = defaults == null ? StepPolicy.DEFAULT : defaults;
        budget = budget == null ? CostBudget.UNLIMITED : budget;
    }

    public Step stepAt(StepId id) {
        return step(id).orElseThrow(
                () -> new IllegalStateException("step " + id + " is not part of " + workflowId));
    }

    public Optional<Step> step(StepId id) {
        return Optional.ofNullable(steps.get(id));
    }
}
