package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.state.StateStore;
import io.pipemesh.core.state.StepRecord;
import io.pipemesh.core.workflow.ExecutionGraph;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * The loop: run the current step, persist what it produced, move to the next one.
 *
 * <p>Two ordering rules hold every iteration, and both are easy to break by
 * accident:
 *
 * <ul>
 *   <li>the step runs <em>before</em> anything is persisted, so provider I/O — a
 *       model call, an MCP invocation — never happens inside an open
 *       transaction;
 *   <li>the step's history entry and the state it produced are written together,
 *       through {@link StateStore#advance}, so a crash cannot lose a step or
 *       replay it.
 * </ul>
 *
 * <p>The loop returns as soon as an execution suspends or ends. It does not wait
 * for anything (§16).
 */
public final class WorkflowExecutor {

    /**
     * A cycle in a workflow is legitimate — a retry loop, a clarification loop.
     * An unbounded one is not, and an engine that spins forever is worse than one
     * that stops with a reason.
     */
    public static final int DEFAULT_STEP_BUDGET = 1_000;

    private static final String INPUT_VARIABLE = "input";

    /** History entries the engine itself writes, rather than a step executor. */
    private static final StepType ENGINE_STEP = StepType.of("engine");

    private final StateStore stateStore;
    private final StepExecutors executors;
    private final Clock clock;
    private final int stepBudget;

    public WorkflowExecutor(StateStore stateStore, StepExecutors executors) {
        this(stateStore, executors, Clock.systemUTC(), DEFAULT_STEP_BUDGET);
    }

    public WorkflowExecutor(StateStore stateStore, StepExecutors executors, Clock clock, int stepBudget) {
        this.stateStore = Objects.requireNonNull(stateStore, "state store");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (stepBudget < 1) {
            throw new IllegalArgumentException("step budget must be positive");
        }
        this.stepBudget = stepBudget;
    }

    public ExecutionRecord start(ExecutionGraph graph, ExecutionId executionId, ExecutionInput input) {
        ExecutionRecord created = stateStore.create(initialRecord(graph, executionId, input));
        return drive(graph, created);
    }

    /**
     * Applies an external signal to the step that is waiting, then keeps going.
     *
     * <p>The caller is responsible for having checked that the execution is
     * resumable; this method assumes it and will fail the step otherwise.
     */
    public ExecutionRecord resume(ExecutionGraph graph, ExecutionRecord record, ResumeSignal signal) {
        Step step = graph.stepAt(record.currentStep());
        StepExecutor executor = executors.forType(step.type());
        if (!(executor instanceof ResumableStepExecutor resumable)) {
            return persist(record, step, new StepResult.Failed("execution.not_resumable",
                    "step '" + step.id() + "' cannot be resumed", false),
                    clock.millis(), clock.millis());
        }

        long startedAt = clock.millis();
        StepResult result = resumable.resume(step, contextOf(record), signal);
        long finishedAt = clock.millis();

        return drive(graph, persist(record, step, result, startedAt, finishedAt));
    }

    /** Runs until the execution suspends, ends, or exhausts its step budget. */
    public ExecutionRecord drive(ExecutionGraph graph, ExecutionRecord from) {
        ExecutionRecord current = from;
        for (int taken = 0; taken < stepBudget; taken++) {
            if (isSettled(current)) {
                return current;
            }
            current = runStep(graph, current);
        }
        return exhausted(current);
    }

    private boolean isSettled(ExecutionRecord record) {
        return record.status().isTerminal() || record.status() == ExecutionStatus.WAITING;
    }

    private ExecutionRecord runStep(ExecutionGraph graph, ExecutionRecord record) {
        Step step = graph.stepAt(record.currentStep());
        StepExecutor executor = executors.forType(step.type());

        long startedAt = clock.millis();
        StepResult result = executor.execute(step, contextOf(record));
        long finishedAt = clock.millis();

        return persist(record, step, result, startedAt, finishedAt);
    }

    private ExecutionRecord persist(
            ExecutionRecord record, Step step, StepResult result, long startedAt, long finishedAt) {

        ExecutionRecord next = switch (result) {
            case StepResult.Continue moved -> record(record, ExecutionStatus.RUNNING,
                    moved.nextStep(), merged(record, moved.variables()));
            case StepResult.Suspend ignored -> record(record, ExecutionStatus.WAITING,
                    record.currentStep(), record.variables());
            case StepResult.Terminate ended -> record(record, ended.status(),
                    record.currentStep(), record.variables());
            case StepResult.Failed ignored -> record(record, ExecutionStatus.FAILED,
                    record.currentStep(), record.variables());
        };
        return stateStore.advance(next, historyEntry(record, step, result, startedAt, finishedAt));
    }

    private StepRecord historyEntry(
            ExecutionRecord record, Step step, StepResult result, long startedAt, long finishedAt) {

        Map<String, JsonNode> attributes = attributesOf(result);
        return new StepRecord(
                record.executionId(),
                step.id(),
                step.type(),
                outcomeOf(result),
                step.config(),
                outputOf(result),
                text(attributes, StepAttributes.LLM_MODEL),
                text(attributes, StepAttributes.LLM_PROMPT_VERSION),
                number(attributes, StepAttributes.LLM_INPUT_TOKENS),
                number(attributes, StepAttributes.LLM_OUTPUT_TOKENS),
                finishedAt - startedAt,
                startedAt,
                finishedAt,
                asJson(attributes));
    }

    /**
     * Whatever the step said about its own run. The engine does not read these
     * beyond lifting four well-known names into typed columns
     * ({@link StepAttributes}).
     */
    private Map<String, JsonNode> attributesOf(StepResult result) {
        return switch (result) {
            case StepResult.Continue moved -> moved.attributes();
            case StepResult.Failed failure -> failure.attributes();
            case StepResult.Suspend ignored -> Map.of();
            case StepResult.Terminate ignored -> Map.of();
        };
    }

    private String text(Map<String, JsonNode> attributes, String name) {
        JsonNode value = attributes.get(name);
        return value == null ? "" : value.asText("");
    }

    private long number(Map<String, JsonNode> attributes, String name) {
        JsonNode value = attributes.get(name);
        return value == null ? 0L : value.asLong(0L);
    }

    private ObjectNode asJson(Map<String, JsonNode> attributes) {
        ObjectNode json = JsonNodeFactory.instance.objectNode();
        attributes.forEach(json::set);
        return json;
    }

    private StepRecord.StepOutcome outcomeOf(StepResult result) {
        return switch (result) {
            case StepResult.Continue ignored -> StepRecord.StepOutcome.SUCCESS;
            case StepResult.Terminate ignored -> StepRecord.StepOutcome.SUCCESS;
            case StepResult.Suspend ignored -> StepRecord.StepOutcome.SUSPENDED;
            case StepResult.Failed ignored -> StepRecord.StepOutcome.FAILED;
        };
    }

    private JsonNode outputOf(StepResult result) {
        ObjectNode output = JsonNodeFactory.instance.objectNode();
        switch (result) {
            case StepResult.Continue moved -> moved.variables().forEach(output::set);
            case StepResult.Terminate ended -> output.put("status", ended.status().name());
            case StepResult.Suspend suspended -> output.put("suspendedBy", suspended.reason().kind());
            case StepResult.Failed failure -> output.put("code", failure.code())
                    .put("message", failure.message());
        }
        return output;
    }

    private ExecutionContext contextOf(ExecutionRecord record) {
        return new ExecutionContext(
                record.executionId(),
                record.workflowId(),
                record.workflowVersion(),
                record.currentStep(),
                record.variables());
    }

    private ObjectNode merged(ExecutionRecord record, Map<String, JsonNode> additions) {
        ObjectNode variables = record.variables();
        additions.forEach(variables::set);
        return variables;
    }

    private ExecutionRecord initialRecord(
            ExecutionGraph graph, ExecutionId executionId, ExecutionInput input) {

        ObjectNode variables = JsonNodeFactory.instance.objectNode();
        variables.set(INPUT_VARIABLE, input.value());
        return new ExecutionRecord(
                executionId,
                graph.workflowId(),
                graph.version(),
                ExecutionStatus.RUNNING,
                graph.entry(),
                variables,
                "",
                0L,
                0L,
                0L);
    }

    private ExecutionRecord record(
            ExecutionRecord from, ExecutionStatus status, StepId currentStep, ObjectNode variables) {

        return new ExecutionRecord(
                from.executionId(),
                from.workflowId(),
                from.workflowVersion(),
                status,
                currentStep,
                variables,
                from.traceContext(),
                from.version(),
                from.createdAtEpochMillis(),
                from.updatedAtEpochMillis());
    }

    private ExecutionRecord exhausted(ExecutionRecord record) {
        ExecutionRecord failed = record(
                record, ExecutionStatus.FAILED, record.currentStep(), record.variables());
        long now = clock.millis();
        return stateStore.advance(failed, new StepRecord(
                record.executionId(),
                record.currentStep(),
                ENGINE_STEP,
                StepRecord.StepOutcome.FAILED,
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode()
                        .put("code", "execution.step_budget_exhausted")
                        .put("budget", stepBudget),
                "", "", 0L, 0L, 0L, now, now,
                JsonNodeFactory.instance.objectNode()));
    }
}
