package io.pipemesh.core.workflow;

import java.util.Collections;
import io.pipemesh.core.cost.CostBudget;
import io.pipemesh.core.policy.StepPolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The serialized form of a workflow (§8). Structural validity — reachable
 * targets, a resolvable entry — is the compiler's concern, not this type's.
 */
public record WorkflowDefinition(
        WorkflowId id,
        WorkflowVersion version,
        StepId entry,
        List<Step> steps,
        StepPolicy defaults,
        CostBudget budget) {

    public WorkflowDefinition(WorkflowId id, WorkflowVersion version, StepId entry, List<Step> steps) {
        this(id, version, entry, steps, StepPolicy.DEFAULT, CostBudget.UNLIMITED);
    }

    public WorkflowDefinition(
            WorkflowId id, WorkflowVersion version, StepId entry,
            List<Step> steps, StepPolicy defaults) {

        this(id, version, entry, steps, defaults, CostBudget.UNLIMITED);
    }

    public WorkflowDefinition {
        Objects.requireNonNull(id, "workflow id");
        Objects.requireNonNull(version, "workflow version");
        Objects.requireNonNull(entry, "entry step");
        Objects.requireNonNull(steps, "steps");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("workflow " + id + " has no steps");
        }
        steps = List.copyOf(steps);
        defaults = defaults == null ? StepPolicy.DEFAULT : defaults;
        budget = budget == null ? CostBudget.UNLIMITED : budget;
    }

    public Optional<Step> step(StepId stepId) {
        return steps.stream().filter(step -> step.id().equals(stepId)).findFirst();
    }

    /**
     * Steps keyed by id, in declaration order — {@code Map.copyOf} is deliberately
     * not used here, as its iteration order is unspecified and varies per JVM.
     */
    public Map<StepId, Step> stepsById() {
        Map<StepId, Step> byId = new LinkedHashMap<>();
        for (Step step : steps) {
            if (byId.put(step.id(), step) != null) {
                throw new IllegalArgumentException("duplicate step id: " + step.id());
            }
        }
        return Collections.unmodifiableMap(byId);
    }
}
