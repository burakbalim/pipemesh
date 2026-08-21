package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.observability.CompositeExecutionObserver;
import io.pipemesh.core.observability.ExecutionEvent;
import io.pipemesh.core.observability.ExecutionObserver;
import io.pipemesh.core.observability.StepEvent;
import io.pipemesh.core.observability.TraceContext;
import io.pipemesh.core.policy.FailurePolicy;
import io.pipemesh.core.policy.StepPolicy;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.cost.SpendMeter;
import io.pipemesh.core.state.StateStore;
import io.pipemesh.core.state.StepRecord;
import io.pipemesh.core.workflow.ExecutionGraph;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

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
    private static final String INTENT_VARIABLE = "intent";

    /** History entries the engine itself writes, rather than a step executor. */
    private static final StepType ENGINE_STEP = StepType.of("engine");

    private final StateStore stateStore;
    private final StepExecutors executors;
    private final Clock clock;
    private final int stepBudget;
    private final ExecutionObserver observer;
    private final SpendMeter meter;

    public WorkflowExecutor(StateStore stateStore, StepExecutors executors) {
        this(stateStore, executors, Clock.systemUTC(), DEFAULT_STEP_BUDGET, ExecutionObserver.NONE);
    }

    public WorkflowExecutor(StateStore stateStore, StepExecutors executors, ExecutionObserver observer) {
        this(stateStore, executors, Clock.systemUTC(), DEFAULT_STEP_BUDGET, observer);
    }

    public WorkflowExecutor(StateStore stateStore, StepExecutors executors, Clock clock, int stepBudget) {
        this(stateStore, executors, clock, stepBudget, ExecutionObserver.NONE);
    }

    public WorkflowExecutor(
            StateStore stateStore,
            StepExecutors executors,
            Clock clock,
            int stepBudget,
            ExecutionObserver observer) {

        this(stateStore, executors, clock, stepBudget, observer, SpendMeter.UNPRICED);
    }

    /**
     * @param meter what each model charges, so an execution can be told what it
     *              has spent and stopped when a budget says so (§39)
     */
    public WorkflowExecutor(
            StateStore stateStore,
            StepExecutors executors,
            Clock clock,
            int stepBudget,
            ExecutionObserver observer,
            SpendMeter meter) {

        this.meter = Objects.requireNonNull(meter, "spend meter");
        this.stateStore = Objects.requireNonNull(stateStore, "state store");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (stepBudget < 1) {
            throw new IllegalArgumentException("step budget must be positive");
        }
        this.stepBudget = stepBudget;
        this.observer = CompositeExecutionObserver.guarded(
                Objects.requireNonNull(observer, "observer"));
    }

    public ExecutionRecord start(
            ExecutionGraph graph, ExecutionId executionId, ExecutionRequest request) {

        return drive(graph, create(graph, executionId, request));
    }

    /**
     * Writes the execution down without running it.
     *
     * <p>Separate from {@link #start} because a distributed deployment enqueues
     * here and lets whichever instance claims it do the driving (§28). The two
     * together are exactly what {@code start} used to do inline.
     */
    public ExecutionRecord create(
            ExecutionGraph graph, ExecutionId executionId, ExecutionRequest request) {

        ExecutionRecord created = stateStore.create(initialRecord(graph, executionId, request));
        observer.executionStarted(eventOf(created));
        return created;
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
            return persistOutcome(record, step, new StepResult.Failed("execution.not_resumable",
                    "step '" + step.id() + "' cannot be resumed", false),
                    clock.millis(), clock.millis(), 1);
        }

        observer.executionResumed(eventOf(record));

        long startedAt = clock.millis();
        StepResult result = safely(() -> resumable.resume(step, contextOf(record), signal));
        long finishedAt = clock.millis();

        return drive(graph, persistOutcome(record, step, result, startedAt, finishedAt, 1));
    }

    /** Runs until the execution suspends, ends, or exhausts a budget. */
    public ExecutionRecord drive(ExecutionGraph graph, ExecutionRecord from) {
        ExecutionRecord current = from;
        for (int taken = 0; taken < stepBudget; taken++) {
            if (isSettled(current)) {
                return announce(current);
            }
            Optional<String> overspent = graph.budget().exceededBy(current.spend());
            if (overspent.isPresent()) {
                return announce(overspent(current, overspent.get()));
            }
            current = runStep(graph, current);
        }
        // The budget counts steps taken, so a workflow whose last step lands exactly
        // on the limit has not overrun anything — it finished.
        return announce(isSettled(current) ? current : exhausted(current));
    }

    private ExecutionRecord announce(ExecutionRecord record) {
        if (record.status() == ExecutionStatus.WAITING) {
            observer.executionSuspended(eventOf(record));
        } else if (record.status().isTerminal()) {
            observer.executionFinished(eventOf(record));
        }
        return record;
    }

    private ExecutionEvent eventOf(ExecutionRecord record) {
        return new ExecutionEvent(
                record.executionId(),
                record.organization(),
                record.workflowId(),
                record.workflowVersion(),
                record.status(),
                record.currentStep(),
                TraceContext.parse(record.traceContext()).orElse(null),
                clock.millis(),
                record.createdAtEpochMillis(),
                record.updatedAtEpochMillis());
    }

    private boolean isSettled(ExecutionRecord record) {
        return record.status().isTerminal() || record.status() == ExecutionStatus.WAITING;
    }

    private ExecutionRecord runStep(ExecutionGraph graph, ExecutionRecord record) {
        Step step = graph.stepAt(record.currentStep());
        StepPolicy policy = StepPolicy.resolve(step, graph.defaults());
        StepExecutor executor = executors.forType(step.type());

        ExecutionRecord current = record;
        for (int attempt = 1; ; attempt++) {
            long startedAt = clock.millis();
            ExecutionRecord attemptOf = current;
            StepResult result = within(policy.timeout(),
                    () -> executor.execute(step, contextOf(attemptOf)));
            long finishedAt = clock.millis();

            if (!shouldRetry(result, policy, attempt)) {
                return conclude(graph, current, step, policy, result, startedAt, finishedAt, attempt);
            }

            // The attempt is written before the next one begins, so a crash mid-retry
            // leaves a record of what was already tried rather than a silent gap.
            current = persistAttempt(current, step, result, startedAt, finishedAt, attempt,
                    ExecutionStatus.RUNNING, current.currentStep());
            pause(policy.retry().delayBefore(attempt));
        }
    }

    private boolean shouldRetry(StepResult result, StepPolicy policy, int attempt) {
        if (!(result instanceof StepResult.Failed failure)) {
            return false;
        }
        // retryable is the executor's judgement, and it is the one that knows
        // whether the call may have already had an effect.
        return failure.retryable() && attempt < policy.retry().maxAttempts();
    }

    /**
     * Applies the failure policy once retrying is over (§18). A step that
     * succeeded, suspended or ended goes straight through.
     */
    private ExecutionRecord conclude(
            ExecutionGraph graph, ExecutionRecord record, Step step, StepPolicy policy,
            StepResult result, long startedAt, long finishedAt, int attempt) {

        if (!(result instanceof StepResult.Failed failure)) {
            return persistOutcome(record, step, result, startedAt, finishedAt, attempt);
        }

        return switch (policy.onFailure().strategy()) {
            case FAIL -> persistOutcome(record, step, result, startedAt, finishedAt, attempt);
            case CONTINUE -> persistOutcome(record, step,
                    continueAfter(step, failure), startedAt, finishedAt, attempt);
            case GOTO -> persistOutcome(record, step,
                    branchAfter(step, failure, policy), startedAt, finishedAt, attempt);
            case FALLBACK -> fallback(
                    graph, record, step, policy, failure, startedAt, finishedAt, attempt);
        };
    }

    /**
     * Runs the step once more with the fallback's model and prompt overlaid on its
     * config. Generic on purpose: any step type with those fields gets a fallback
     * without the engine knowing what they mean.
     */
    private ExecutionRecord fallback(
            ExecutionGraph graph, ExecutionRecord record, Step step, StepPolicy policy,
            StepResult.Failed failure, long startedAt, long finishedAt, int attempt) {

        ExecutionRecord afterFirst = persistAttempt(record, step, failure, startedAt, finishedAt,
                attempt, ExecutionStatus.RUNNING, record.currentStep());

        Step fallbackStep = withOverrides(step, policy.onFailure());
        StepExecutor executor = executors.forType(fallbackStep.type());

        long fallbackStartedAt = clock.millis();
        StepResult result = within(policy.timeout(),
                () -> executor.execute(fallbackStep, contextOf(afterFirst)));
        long fallbackFinishedAt = clock.millis();

        return persistOutcome(
                afterFirst, fallbackStep, result, fallbackStartedAt, fallbackFinishedAt, attempt + 1);
    }

    private Step withOverrides(Step step, FailurePolicy policy) {
        ObjectNode config = step.config().deepCopy();
        policy.fallbackModelIfAny().ifPresent(model -> config.put("model", model));
        policy.fallbackPromptIfAny().ifPresent(prompt -> config.put("prompt", prompt));
        return new Step(step.id(), step.type(), config);
    }

    /** Records the failure in a variable and carries on down {@code next}. */
    private StepResult continueAfter(Step step, StepResult.Failed failure) {
        String next = step.config().path("next").asText("");
        if (next.isBlank()) {
            return failure;
        }
        ObjectNode error = JsonNodeFactory.instance.objectNode()
                .put("code", failure.code())
                .put("message", failure.message());

        return new StepResult.Continue(StepId.of(next),
                Map.of(step.id().value() + "Error", error), failure.attributes());
    }

    private StepResult branchAfter(Step step, StepResult.Failed failure, StepPolicy policy) {
        return policy.onFailure().targetIfAny()
                .<StepResult>map(target -> new StepResult.Continue(target,
                        Map.of(step.id().value() + "Error", JsonNodeFactory.instance.objectNode()
                                .put("code", failure.code())
                                .put("message", failure.message())),
                        failure.attributes()))
                .orElse(failure);
    }

    /**
     * Runs a step under a deadline.
     *
     * <p>The step runs on a virtual thread, and when the deadline passes the
     * engine stops waiting. It does not stop the call: a provider that ignores
     * interruption keeps going until its own timeout. That is deliberate — the
     * alternative is holding an execution hostage to a request nobody can cancel,
     * and on a virtual thread the abandoned work costs almost nothing.
     */
    private StepResult within(Duration timeout, Supplier<StepResult> step) {
        if (timeout == null) {
            return safely(step);
        }
        CompletableFuture<StepResult> outcome = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> outcome.complete(safely(step)));

        try {
            return outcome.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException expired) {
            return new StepResult.Failed("step.timeout",
                    "step did not finish within " + timeout.toMillis() + "ms", true);
        } catch (ExecutionException failure) {
            return new StepResult.Failed("step.threw", String.valueOf(failure.getCause()), false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new StepResult.Failed("step.interrupted", "the runtime was shutting down", false);
        }
    }

    private void pause(Duration delay) {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A step that throws is a failed step, not a failed engine.
     *
     * <p>Steps reach out to models, tools and other people's services, and those
     * throw. Letting one escape would lose the execution entirely: the state it
     * reached would never be written, and nothing would record why. Turning it
     * into {@link StepResult.Failed} keeps the run on the books.
     */
    private StepResult safely(Supplier<StepResult> step) {
        try {
            return step.get();
        } catch (RuntimeException failure) {
            return new StepResult.Failed(
                    "step.threw",
                    failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                    false);
        }
    }

    private ExecutionRecord persistOutcome(
            ExecutionRecord record, Step step, StepResult result,
            long startedAt, long finishedAt, int attempt) {

        ExecutionRecord next = switch (result) {
            case StepResult.Continue moved -> record.movedTo(ExecutionStatus.RUNNING,
                    moved.nextStep(), merged(record, moved.variables()));
            case StepResult.Suspend ignored -> record.movedTo(ExecutionStatus.WAITING,
                    record.currentStep(), record.variables());
            case StepResult.Terminate ended -> record.movedTo(ended.status(),
                    record.currentStep(), record.variables());
            case StepResult.Failed ignored -> record.movedTo(ExecutionStatus.FAILED,
                    record.currentStep(), record.variables());
        };
        return write(record, next, step, result, startedAt, finishedAt, attempt);
    }

    /** Writes an attempt that did not settle the step — the execution stays where it is. */
    private ExecutionRecord persistAttempt(
            ExecutionRecord record, Step step, StepResult result, long startedAt, long finishedAt,
            int attempt, ExecutionStatus status, StepId currentStep) {

        ExecutionRecord next = record.movedTo(status, currentStep, record.variables());
        return write(record, next, step, result, startedAt, finishedAt, attempt);
    }

    private ExecutionRecord write(
            ExecutionRecord from, ExecutionRecord next, Step step, StepResult result,
            long startedAt, long finishedAt, int attempt) {

        StepRecord history = historyEntry(from, step, result, startedAt, finishedAt, attempt);

        // The step's spend joins the execution's total in the same write as the
        // step itself — split them and a crash between the two loses the money or
        // counts it twice, which is the rule the whole store is built on (§15).
        ExecutionRecord advanced =
                stateStore.advance(next.withSpend(meter.after(from.spend(), history)), history);

        observer.stepFinished(new StepEvent(
                eventOf(advanced), step.id(), step.type(), history.outcome(),
                history.latencyMillis(), attributesOf(result), attempt));

        return advanced;
    }

    private StepRecord historyEntry(
            ExecutionRecord record, Step step, StepResult result,
            long startedAt, long finishedAt, int attempt) {

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
                asJson(attributes),
                attempt);
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
                record.organization(),
                record.workflowId(),
                record.workflowVersion(),
                record.currentStep(),
                record.variables(),
                record.traceContext(),
                record.principal());
    }

    private ObjectNode merged(ExecutionRecord record, Map<String, JsonNode> additions) {
        ObjectNode variables = record.variables();
        additions.forEach(variables::set);
        return variables;
    }

    private ExecutionRecord initialRecord(
            ExecutionGraph graph, ExecutionId executionId, ExecutionRequest request) {

        ObjectNode variables = JsonNodeFactory.instance.objectNode();
        variables.set(INPUT_VARIABLE, request.input().value());
        request.intentIfAny().ifPresent(intent -> variables.set(INTENT_VARIABLE, intent));

        // A caller already inside a trace passes its context in, so the workflow
        // hangs under the request that asked for it rather than starting a trace
        // of its own.
        TraceContext trace = request.traceParentIfAny()
                .flatMap(TraceContext::parse)
                .map(TraceContext::child)
                .orElseGet(TraceContext::generate);

        return new ExecutionRecord(
                executionId,
                request.organization(),
                graph.workflowId(),
                graph.version(),
                // Written down but not yet moving. Inline, the very next line
                // drives it; dispatched, this is what a driver comes looking for
                // (§28). Calling it RUNNING before anyone runs it would make the
                // status say something untrue to every reader of it.
                ExecutionStatus.CREATED,
                graph.entry(),
                variables,
                trace.toTraceParent(),
                0L,
                0L,
                0L,
                request.principal());
    }

    /**
     * Stops an execution that has spent its budget (§39).
     *
     * <p>Checked before the next step, never in the middle of one: a provider call
     * already made is already paid for, and abandoning it would waste the money
     * rather than save it.
     */
    private ExecutionRecord overspent(ExecutionRecord record, String reason) {
        return engineFailure(record, "execution.budget_exhausted", reason);
    }

    private ExecutionRecord exhausted(ExecutionRecord record) {
        return engineFailure(record, "execution.step_budget_exhausted",
                "took more than " + stepBudget + " steps");
    }

    /**
     * Fails an execution for a reason the engine itself reached, with a history
     * entry saying so — otherwise the run ends and nothing anywhere says why.
     */
    private ExecutionRecord engineFailure(ExecutionRecord record, String code, String reason) {
        ExecutionRecord failed =
                record.movedTo(ExecutionStatus.FAILED, record.currentStep(), record.variables());
        long now = clock.millis();
        return stateStore.advance(failed, new StepRecord(
                record.executionId(),
                record.currentStep(),
                ENGINE_STEP,
                StepRecord.StepOutcome.FAILED,
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode().put("code", code).put("reason", reason),
                "", "", 0L, 0L, 0L, now, now,
                JsonNodeFactory.instance.objectNode(), 1));
    }
}
