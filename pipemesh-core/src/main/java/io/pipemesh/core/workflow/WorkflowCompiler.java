package io.pipemesh.core.workflow;

import io.pipemesh.core.execution.StepExecutor;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.cost.SpendMeter;
import io.pipemesh.core.policy.StepPolicy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a definition into a graph, or refuses to (§25).
 *
 * <p>Everything it checks is a mistake that would otherwise surface halfway
 * through a run — after a model has been called and paid for, or worse, after a
 * side effect has already happened.
 */
public final class WorkflowCompiler {

    private final StepExecutors executors;
    private final SpendMeter meter;

    public WorkflowCompiler(StepExecutors executors) {
        this(executors, SpendMeter.UNPRICED);
    }

    /**
     * @param meter which models have a registered price, so a workflow that sets
     *              a money budget can be refused one that does not (§39)
     */
    public WorkflowCompiler(StepExecutors executors, SpendMeter meter) {
        this.executors = Objects.requireNonNull(executors, "executors");
        this.meter = Objects.requireNonNull(meter, "spend meter");
    }

    public ExecutionGraph compile(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        List<String> problems = new ArrayList<>();

        Map<StepId, Step> steps = collectSteps(definition, problems);
        if (!steps.containsKey(definition.entry())) {
            problems.add("entry step '" + definition.entry() + "' does not exist");
        }
        problems.addAll(unknownStepTypes(steps));
        problems.addAll(danglingEdges(steps, definition.defaults()));
        problems.addAll(unreachableSteps(definition.entry(), steps, definition.defaults()));
        problems.addAll(unpricedModels(definition, steps));

        if (!problems.isEmpty()) {
            throw new WorkflowCompilationException(definition.id(), problems);
        }
        return new ExecutionGraph(
                definition.id(), definition.version(), definition.entry(), steps,
                definition.defaults(), definition.budget());
    }

    /**
     * A money budget that cannot see what a step costs is not a budget — it would
     * let exactly the runs it exists to stop go through untouched. Refused here
     * rather than at three in the morning.
     */
    private List<String> unpricedModels(WorkflowDefinition definition, Map<StepId, Step> steps) {
        if (!definition.budget().limitsMoney()) {
            return List.of();
        }
        List<String> problems = new ArrayList<>();
        for (Step step : steps.values()) {
            executors.find(step.type()).ifPresent(executor -> executor.models(step).stream()
                    .filter(model -> !meter.canPrice(model))
                    .forEach(model -> problems.add(
                            "step '" + step.id() + "' uses model '" + model + "', which has no"
                                    + " registered price, but this workflow sets a cost budget")));
        }
        return problems;
    }

    private Map<StepId, Step> collectSteps(WorkflowDefinition definition, List<String> problems) {
        try {
            return definition.stepsById();
        } catch (IllegalArgumentException duplicate) {
            problems.add(duplicate.getMessage());
            return Map.of();
        }
    }

    private List<String> unknownStepTypes(Map<StepId, Step> steps) {
        return steps.values().stream()
                .filter(step -> executorFor(step).isEmpty())
                .map(step -> "step '" + step.id() + "' has unknown type '" + step.type() + "'")
                .toList();
    }

    private List<String> danglingEdges(Map<StepId, Step> steps, StepPolicy defaults) {
        List<String> problems = new ArrayList<>();
        for (Step step : steps.values()) {
            for (StepId target : outgoing(step, defaults)) {
                if (!steps.containsKey(target)) {
                    problems.add("step '" + step.id() + "' points at missing step '" + target + "'");
                }
            }
        }
        return problems;
    }

    private List<String> unreachableSteps(StepId entry, Map<StepId, Step> steps, StepPolicy defaults) {
        if (!steps.containsKey(entry)) {
            return List.of();
        }
        Set<StepId> reached = reachableFrom(entry, steps, defaults);
        return steps.keySet().stream()
                .filter(id -> !reached.contains(id))
                .map(id -> "step '" + id + "' is unreachable")
                .toList();
    }

    private Set<StepId> reachableFrom(StepId entry, Map<StepId, Step> steps, StepPolicy defaults) {
        Set<StepId> reached = new LinkedHashSet<>();
        List<StepId> pending = new ArrayList<>(List.of(entry));
        while (!pending.isEmpty()) {
            StepId current = pending.remove(pending.size() - 1);
            if (!reached.add(current)) {
                continue;
            }
            Step step = steps.get(current);
            if (step != null) {
                pending.addAll(outgoing(step, defaults));
            }
        }
        return reached;
    }

    /**
     * Every step this one can hand control to: the edges its executor declares,
     * plus the one a failure policy can branch to.
     *
     * <p>A {@code goto} target is an edge like any other. Leaving it out would
     * make the step it names look unreachable, and would let a typo in it survive
     * until the day something actually failed.
     */
    private Set<StepId> outgoing(Step step, StepPolicy defaults) {
        Set<StepId> targets = new LinkedHashSet<>();
        executorFor(step).ifPresent(executor -> targets.addAll(executor.outgoing(step)));
        StepPolicy.resolve(step, defaults).onFailure().targetIfAny().ifPresent(targets::add);
        return targets;
    }

    private Optional<StepExecutor> executorFor(Step step) {
        return executors.find(step.type());
    }
}
