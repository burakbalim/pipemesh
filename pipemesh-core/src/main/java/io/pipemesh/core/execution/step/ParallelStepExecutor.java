package io.pipemesh.core.execution.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.pipemesh.core.execution.ExecutionContext;
import io.pipemesh.core.execution.StepExecutor;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.StepResult;
import io.pipemesh.core.workflow.ExecutionGraph;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;
import io.pipemesh.core.workflow.WorkflowRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Runs independent branches at the same time and continues at the join (§9.5).
 *
 * <p>Branches run inside this step rather than as positions of their own. An
 * execution has one row, one version and one writer; two branches advancing it
 * would spend their lives losing to each other's version check. What is expensive
 * here is the I/O — model and capability calls — and that is what runs
 * concurrently, while persistence stays where the durability model needs it: one
 * step, one write (§29).
 *
 * <p>The cost is stated rather than hidden: the steps inside a branch get no
 * history rows and no durability of their own. A process that dies mid-parallel
 * makes recovery re-run the <em>whole</em> step, which is why
 * {@link #repeatable} asks every branch before allowing that.
 */
public final class ParallelStepExecutor implements StepExecutor {

    private static final String BRANCHES = "branches";
    private static final String JOIN = "join";

    private static final JsonNode SCHEMA = StepSchemas.parse("""
            {
              "properties": {
                "branches": {"type": "array", "items": {"type": "string"}},
                "join":     {"type": "string"}
              },
              "required": ["branches", "join"]
            }
            """);

    private final WorkflowRegistry workflows;
    private final Supplier<StepExecutors> executors;

    /**
     * @param executors supplied lazily because the set of executors contains this
     *                  one — a parallel step runs steps, and one of the steps it
     *                  could run is another parallel step
     */
    public ParallelStepExecutor(WorkflowRegistry workflows, Supplier<StepExecutors> executors) {
        this.workflows = Objects.requireNonNull(workflows, "workflow registry");
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    @Override
    public boolean supports(StepType type) {
        return StepType.of("parallel").equals(type);
    }

    @Override
    public Optional<JsonNode> configSchema() {
        return Optional.of(SCHEMA);
    }

    @Override
    public List<StepId> outgoing(Step step) {
        List<StepId> targets = new ArrayList<>(branchesOf(step));
        joinOf(step).ifPresent(targets::add);
        return List.copyOf(targets);
    }

    @Override
    public StepResult execute(Step step, ExecutionContext context) {
        Optional<StepId> join = joinOf(step);
        if (join.isEmpty()) {
            return new StepResult.Failed("parallel.no_join",
                    "a parallel step must say where its branches come back together", false);
        }
        List<StepId> branches = branchesOf(step);
        if (branches.isEmpty()) {
            return new StepResult.Failed("parallel.no_branches",
                    "a parallel step with no branches is a step that does nothing", false);
        }

        Optional<ExecutionGraph> graph = workflows.find(context.workflowId(), context.workflowVersion());
        if (graph.isEmpty()) {
            return new StepResult.Failed("parallel.unknown_workflow",
                    "workflow '" + context.workflowId() + "' is no longer registered", false);
        }

        BranchRun runner = new BranchRun(graph.get(), executors.get(), join.get());
        List<BranchRun.Outcome> outcomes = runAll(branches, runner, context);

        return settle(outcomes, join.get());
    }

    /**
     * Starts every branch and waits for all of them.
     *
     * <p>All, not the first: a join is a join. Each branch is given its own copy
     * of the context — {@link ExecutionContext} is immutable and hands out copies
     * of its variables — so no branch can read another's half-written work and
     * make the result depend on which finished first.
     */
    private List<BranchRun.Outcome> runAll(
            List<StepId> branches, BranchRun runner, ExecutionContext context) {

        List<CompletableFuture<BranchRun.Outcome>> running = branches.stream()
                .map(branch -> {
                    CompletableFuture<BranchRun.Outcome> outcome = new CompletableFuture<>();
                    Thread.ofVirtual().start(() -> {
                        try {
                            outcome.complete(runner.walk(branch, context));
                        } catch (RuntimeException failure) {
                            outcome.complete(BranchRun.Outcome.failed(branch.value(), 0,
                                    "parallel.branch_threw", String.valueOf(failure)));
                        }
                    });
                    return outcome;
                })
                .toList();

        return running.stream().map(CompletableFuture::join).toList();
    }

    private StepResult settle(List<BranchRun.Outcome> outcomes, StepId join) {
        Optional<BranchRun.Outcome> broken = outcomes.stream()
                .filter(outcome -> outcome.failure().isPresent())
                .findFirst();

        if (broken.isPresent()) {
            // Carrying on with partial results would hide a gap the workflow never
            // agreed to. The step's own onFailure policy decides what happens next.
            StepResult.Failed failure = broken.get().failure().get();
            return new StepResult.Failed(failure.code(),
                    "branch '" + broken.get().branch() + "': " + failure.message(),
                    failure.retryable(), attributesOf(outcomes));
        }

        Map<String, JsonNode> merged = new LinkedHashMap<>();
        Map<String, String> writtenBy = new LinkedHashMap<>();

        for (BranchRun.Outcome outcome : outcomes) {
            for (Map.Entry<String, JsonNode> write : outcome.writes().entrySet()) {
                String earlier = writtenBy.put(write.getKey(), outcome.branch());
                if (earlier != null) {
                    // Last-write-wins here would depend on which branch finished
                    // first, which is a bug nobody can reproduce.
                    return new StepResult.Failed("parallel.conflicting_writes",
                            "branches '" + earlier + "' and '" + outcome.branch()
                                    + "' both wrote '" + write.getKey() + "'",
                            false, attributesOf(outcomes));
                }
                merged.put(write.getKey(), write.getValue());
            }
        }

        return new StepResult.Continue(join, merged, attributesOf(outcomes));
    }

    /** Whether every step in every branch may be run a second time. */
    @Override
    public boolean repeatable(Step step, ExecutionContext context) {
        Optional<StepId> join = joinOf(step);
        Optional<ExecutionGraph> graph = workflows.find(context.workflowId(), context.workflowVersion());
        if (join.isEmpty() || graph.isEmpty()) {
            return false;
        }

        BranchRun runner = new BranchRun(graph.get(), executors.get(), join.get());
        return branchesOf(step).stream()
                .allMatch(branch -> runner.repeatable(branch, context));
    }

    private Map<String, JsonNode> attributesOf(List<BranchRun.Outcome> outcomes) {
        JsonNodeFactory json = JsonNodeFactory.instance;
        var branches = json.arrayNode();
        outcomes.forEach(outcome -> branches.addObject()
                .put("branch", outcome.branch())
                .put("steps", outcome.stepsRun()));

        return Map.of(
                "parallel.branches", json.numberNode(outcomes.size()),
                "parallel.detail", branches);
    }

    private List<StepId> branchesOf(Step step) {
        List<StepId> branches = new ArrayList<>();
        step.config().path(BRANCHES).forEach(branch -> branches.add(StepId.of(branch.asText())));
        return List.copyOf(branches);
    }

    private Optional<StepId> joinOf(Step step) {
        String join = step.config().path(JOIN).asText("");
        return join.isBlank() ? Optional.empty() : Optional.of(StepId.of(join));
    }
}
