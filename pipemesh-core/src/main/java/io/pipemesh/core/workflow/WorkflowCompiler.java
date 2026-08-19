package io.pipemesh.core.workflow;

import io.pipemesh.core.execution.StepExecutor;
import io.pipemesh.core.execution.StepExecutors;

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

    public WorkflowCompiler(StepExecutors executors) {
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    public ExecutionGraph compile(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        List<String> problems = new ArrayList<>();

        Map<StepId, Step> steps = collectSteps(definition, problems);
        if (!steps.containsKey(definition.entry())) {
            problems.add("entry step '" + definition.entry() + "' does not exist");
        }
        problems.addAll(unknownStepTypes(steps));
        problems.addAll(danglingEdges(steps));
        problems.addAll(unreachableSteps(definition.entry(), steps));

        if (!problems.isEmpty()) {
            throw new WorkflowCompilationException(definition.id(), problems);
        }
        return new ExecutionGraph(definition.id(), definition.version(), definition.entry(), steps);
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

    private List<String> danglingEdges(Map<StepId, Step> steps) {
        List<String> problems = new ArrayList<>();
        for (Step step : steps.values()) {
            for (StepId target : outgoing(step)) {
                if (!steps.containsKey(target)) {
                    problems.add("step '" + step.id() + "' points at missing step '" + target + "'");
                }
            }
        }
        return problems;
    }

    private List<String> unreachableSteps(StepId entry, Map<StepId, Step> steps) {
        if (!steps.containsKey(entry)) {
            return List.of();
        }
        Set<StepId> reached = reachableFrom(entry, steps);
        return steps.keySet().stream()
                .filter(id -> !reached.contains(id))
                .map(id -> "step '" + id + "' is unreachable")
                .toList();
    }

    private Set<StepId> reachableFrom(StepId entry, Map<StepId, Step> steps) {
        Set<StepId> reached = new LinkedHashSet<>();
        List<StepId> pending = new ArrayList<>(List.of(entry));
        while (!pending.isEmpty()) {
            StepId current = pending.remove(pending.size() - 1);
            if (!reached.add(current)) {
                continue;
            }
            Step step = steps.get(current);
            if (step != null) {
                pending.addAll(outgoing(step));
            }
        }
        return reached;
    }

    private Set<StepId> outgoing(Step step) {
        Optional<StepExecutor> executor = executorFor(step);
        if (executor.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(executor.get().outgoing(step));
    }

    private Optional<StepExecutor> executorFor(Step step) {
        return executors.find(step.type());
    }
}
