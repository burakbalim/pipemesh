package io.pipemesh.core.execution;

import io.pipemesh.core.workflow.StepType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The set of step types this runtime understands.
 *
 * <p>Extending the engine means handing it another {@link StepExecutor} here.
 * Nothing else in core learns about the new type — not the compiler, not the
 * loop (§27).
 */
public final class StepExecutors {

    private final List<StepExecutor> executors;

    public StepExecutors(List<StepExecutor> executors) {
        this.executors = List.copyOf(Objects.requireNonNull(executors, "executors"));
    }

    public static StepExecutors of(StepExecutor... executors) {
        return new StepExecutors(List.of(executors));
    }

    public Optional<StepExecutor> find(StepType type) {
        return executors.stream().filter(executor -> executor.supports(type)).findFirst();
    }

    public StepExecutor forType(StepType type) {
        return find(type).orElseThrow(
                () -> new IllegalStateException("no executor for step type '" + type + "'"));
    }
}
