package io.pipemesh.core.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.execution.ExecutionContext;
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.StepExecutor;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.StepResult;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.state.StepRecord;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.ExecutionGraph;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliabilityPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A step whose behaviour is decided per attempt, so a flake can be scripted. */
    private static final class ScriptedStep implements StepExecutor {

        private final AtomicInteger calls = new AtomicInteger();
        private final Function<Integer, StepResult> script;

        ScriptedStep(Function<Integer, StepResult> script) {
            this.script = script;
        }

        @Override
        public boolean supports(StepType type) {
            return StepType.CAPABILITY.equals(type);
        }

        @Override
        public StepResult execute(Step step, ExecutionContext context) {
            return script.apply(calls.incrementAndGet());
        }

        @Override
        public List<StepId> outgoing(Step step) {
            String next = step.config().path("next").asText("");
            return next.isBlank() ? List.of() : List.of(StepId.of(next));
        }

        int calls() {
            return calls.get();
        }
    }

    private final InMemoryStateStore stateStore = new InMemoryStateStore();

    private ExecutionRecord run(String workflowJson, ScriptedStep step) {
        StepExecutors executors = StepExecutors.of(step, new TerminalStepExecutor());
        ExecutionGraph graph = new InMemoryWorkflowRegistry(new WorkflowCompiler(executors))
                .register(new WorkflowDefinitionReader().read(workflowJson));

        try {
            return new WorkflowExecutor(stateStore, executors).start(
                    graph, ExecutionId.generate(),
                    ExecutionRequest.of(WorkflowId.of("policy_test"),
                            new ExecutionInput((ObjectNode) JSON.readTree("{}"))));
        } catch (Exception malformed) {
            throw new IllegalStateException(malformed);
        }
    }

    private static StepResult flaky(int call, int failuresBefore) {
        return call <= failuresBefore
                ? new StepResult.Failed("provider.unavailable", "try again", true)
                : StepResult.Continue.to(StepId.of("done"));
    }

    private List<StepRecord> historyOf(ExecutionRecord record) {
        return stateStore.historyOf(record.executionId());
    }

    @Test
    void retriesATransientFailureUntilItWorks() {
        ScriptedStep step = new ScriptedStep(call -> flaky(call, 2));

        ExecutionRecord finished = run("""
                {
                  "id": "policy_test", "version": "1.0", "entry": "call",
                  "steps": [
                    {"id": "call", "type": "capability", "capability": "x", "next": "done",
                     "retry": {"maxAttempts": 3, "initialDelay": "1ms"}},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, step);

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertEquals(3, step.calls());
    }

    @Test
    void writesEveryAttemptToTheHistory() {
        ExecutionRecord finished = run("""
                {
                  "id": "policy_test", "version": "1.0", "entry": "call",
                  "steps": [
                    {"id": "call", "type": "capability", "capability": "x", "next": "done",
                     "retry": {"maxAttempts": 3, "initialDelay": "1ms"}},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, new ScriptedStep(call -> flaky(call, 2)));

        List<StepRecord> attempts = historyOf(finished).stream()
                .filter(entry -> entry.stepId().equals(StepId.of("call")))
                .toList();

        assertEquals(List.of(1, 2, 3), attempts.stream().map(StepRecord::attempt).toList());
        assertEquals(StepRecord.StepOutcome.FAILED, attempts.get(0).outcome());
        assertEquals(StepRecord.StepOutcome.SUCCESS, attempts.get(2).outcome());
    }

    @Test
    void neverRetriesAFailureTheExecutorCalledPermanent() {
        ScriptedStep step = new ScriptedStep(
                call -> new StepResult.Failed("capability.unknown", "no such thing", false));

        ExecutionRecord finished = run("""
                {
                  "id": "policy_test", "version": "1.0", "entry": "call",
                  "steps": [
                    {"id": "call", "type": "capability", "capability": "x", "next": "done",
                     "retry": {"maxAttempts": 5, "initialDelay": "1ms"}},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, step);

        assertEquals(ExecutionStatus.FAILED, finished.status());
        assertEquals(1, step.calls(), "a permanent failure must not be tried again");
    }

    @Test
    void givesUpWhenTheAttemptsRunOut() {
        ScriptedStep step = new ScriptedStep(
                call -> new StepResult.Failed("provider.unavailable", "still down", true));

        ExecutionRecord finished = run("""
                {
                  "id": "policy_test", "version": "1.0", "entry": "call",
                  "steps": [
                    {"id": "call", "type": "capability", "capability": "x", "next": "done",
                     "retry": {"maxAttempts": 2, "initialDelay": "1ms"}},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, step);

        assertEquals(ExecutionStatus.FAILED, finished.status());
        assertEquals(2, step.calls());
    }

    @Test
    void carriesOnPastAFailureWhenToldTo() {
        ExecutionRecord finished = run("""
                {
                  "id": "policy_test", "version": "1.0", "entry": "call",
                  "steps": [
                    {"id": "call", "type": "capability", "capability": "x", "next": "done",
                     "onFailure": {"strategy": "continue"}},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, new ScriptedStep(call -> new StepResult.Failed("boom", "nope", false)));

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertEquals("boom", finished.variables().path("callError").path("code").asText());
    }

    @Test
    void branchesToAnotherStepOnFailure() {
        ExecutionRecord finished = run("""
                {
                  "id": "policy_test", "version": "1.0", "entry": "call",
                  "steps": [
                    {"id": "call", "type": "capability", "capability": "x", "next": "done",
                     "onFailure": {"strategy": "goto", "goto": "gaveUp"}},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"},
                    {"id": "gaveUp", "type": "terminal", "status": "CANCELLED"}
                  ]
                }
                """, new ScriptedStep(call -> new StepResult.Failed("boom", "nope", false)));

        assertEquals(ExecutionStatus.CANCELLED, finished.status());
        assertEquals(StepId.of("gaveUp"), finished.currentStep());
    }

    @Test
    void failsTheExecutionWhenNoPolicySaysOtherwise() {
        ExecutionRecord finished = run("""
                {
                  "id": "policy_test", "version": "1.0", "entry": "call",
                  "steps": [
                    {"id": "call", "type": "capability", "capability": "x", "next": "done"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, new ScriptedStep(call -> new StepResult.Failed("boom", "nope", true)));

        assertEquals(ExecutionStatus.FAILED, finished.status());
    }

    @Test
    void aStepPolicyOverridesTheWorkflowsDefault() {
        ScriptedStep step = new ScriptedStep(
                call -> new StepResult.Failed("provider.unavailable", "down", true));

        run("""
                {
                  "id": "policy_test", "version": "1.0", "entry": "call",
                  "retry": {"maxAttempts": 5, "initialDelay": "1ms"},
                  "steps": [
                    {"id": "call", "type": "capability", "capability": "x", "next": "done",
                     "retry": {"maxAttempts": 2, "initialDelay": "1ms"}},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, step);

        assertEquals(2, step.calls(), "the narrower policy wins");
    }

    @Test
    void aWorkflowDefaultAppliesToAStepThatSaysNothing() {
        ScriptedStep step = new ScriptedStep(call -> flaky(call, 2));

        ExecutionRecord finished = run("""
                {
                  "id": "policy_test", "version": "1.0", "entry": "call",
                  "retry": {"maxAttempts": 4, "initialDelay": "1ms"},
                  "steps": [
                    {"id": "call", "type": "capability", "capability": "x", "next": "done"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, step);

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertEquals(3, step.calls());
    }

    @Test
    void failsAStepThatOverrunsItsDeadline() {
        ScriptedStep step = new ScriptedStep(call -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return StepResult.Continue.to(StepId.of("done"));
        });

        ExecutionRecord finished = run("""
                {
                  "id": "policy_test", "version": "1.0", "entry": "call",
                  "steps": [
                    {"id": "call", "type": "capability", "capability": "x", "next": "done",
                     "timeout": "50ms"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, step);

        assertEquals(ExecutionStatus.FAILED, finished.status());
        assertEquals("step.timeout", historyOf(finished).get(0).output().path("code").asText());
    }

    @Test
    void treatsATimeoutAsWorthRetrying() {
        AtomicInteger calls = new AtomicInteger();
        ScriptedStep step = new ScriptedStep(call -> {
            if (calls.incrementAndGet() == 1) {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return StepResult.Continue.to(StepId.of("done"));
        });

        ExecutionRecord finished = run("""
                {
                  "id": "policy_test", "version": "1.0", "entry": "call",
                  "steps": [
                    {"id": "call", "type": "capability", "capability": "x", "next": "done",
                     "timeout": "50ms", "retry": {"maxAttempts": 2, "initialDelay": "1ms"}},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, step);

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
    }

    @Test
    void retriesDoNotEatTheStepBudget() {
        ScriptedStep step = new ScriptedStep(call -> flaky(call, 2));

        ExecutionRecord finished = new WorkflowExecutor(
                stateStore,
                StepExecutors.of(step, new TerminalStepExecutor()),
                java.time.Clock.systemUTC(),
                2).start(
                new InMemoryWorkflowRegistry(new WorkflowCompiler(
                        StepExecutors.of(step, new TerminalStepExecutor())))
                        .register(new WorkflowDefinitionReader().read("""
                                {
                                  "id": "policy_test", "version": "1.0", "entry": "call",
                                  "steps": [
                                    {"id": "call", "type": "capability", "capability": "x",
                                     "next": "done",
                                     "retry": {"maxAttempts": 3, "initialDelay": "1ms"}},
                                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                                  ]
                                }
                                """)),
                ExecutionId.generate(),
                ExecutionRequest.of(WorkflowId.of("policy_test"), ExecutionInput.empty()));

        assertEquals(ExecutionStatus.COMPLETED, finished.status(),
                "a budget of two steps must still cover a step that needed three attempts");
    }

    @Test
    void backoffGrowsAndStaysWithinItsCap() {
        RetryPolicy policy = RetryPolicy.from(JSON.createObjectNode()
                .put("maxAttempts", 5)
                .put("backoff", "exponential")
                .put("initialDelay", "100ms")
                .put("maxDelay", "400ms"));

        assertTrue(policy.delayBefore(1).toMillis() <= 100);
        assertTrue(policy.delayBefore(4).toMillis() <= 400, "the cap holds");
        assertTrue(policy.delayBefore(4).toMillis() >= 200, "jitter never removes the wait entirely");
    }
}
