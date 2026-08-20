package io.pipemesh.core.policy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityId;
import io.pipemesh.core.capability.CapabilityKind;
import io.pipemesh.core.capability.CapabilityProvider;
import io.pipemesh.core.capability.CapabilityResult;
import io.pipemesh.core.capability.InMemoryCapabilityRegistry;
import io.pipemesh.core.execution.ExecutionContext;
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.StepExecutor;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.StepResult;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.CapabilityStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.ExecutionRecord;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallbackPolicyTest {

    /** Fails for one model and succeeds for another, so a fallback is observable. */
    private static final class ModelSensitiveStep implements StepExecutor {

        final List<String> modelsTried = new ArrayList<>();
        final List<String> promptsTried = new ArrayList<>();

        @Override
        public boolean supports(StepType type) {
            return StepType.LLM.equals(type);
        }

        @Override
        public StepResult execute(Step step, ExecutionContext context) {
            String model = step.config().path("model").asText("");
            modelsTried.add(model);
            promptsTried.add(step.config().path("prompt").asText(""));

            return "fast".equals(model)
                    ? new StepResult.Failed("llm.unavailable", "model is down", true)
                    : StepResult.Continue.to(StepId.of("done"));
        }

        @Override
        public List<StepId> outgoing(Step step) {
            String next = step.config().path("next").asText("");
            return next.isBlank() ? List.of() : List.of(StepId.of(next));
        }
    }

    /** Counts invocations, so a refused retry is visible. */
    private record CountingProvider(String type, AtomicInteger calls) implements CapabilityProvider {

        @Override
        public CapabilityResult invoke(CapabilityDescriptor capability, com.fasterxml.jackson.databind.JsonNode input) {
            calls.incrementAndGet();
            return new CapabilityResult.Failure("payment.timeout", "no answer", true);
        }
    }

    private final InMemoryStateStore stateStore = new InMemoryStateStore();

    private ExecutionRecord run(String workflowJson, StepExecutors executors) {
        ExecutionGraph graph = new InMemoryWorkflowRegistry(new WorkflowCompiler(executors))
                .register(new WorkflowDefinitionReader().read(workflowJson));

        return new WorkflowExecutor(stateStore, executors).start(
                graph, ExecutionId.generate(),
                ExecutionRequest.of(WorkflowId.of("fallback_test"), ExecutionInput.empty()));
    }

    @Test
    void runsTheStepAgainAgainstTheFallbackModel() {
        ModelSensitiveStep step = new ModelSensitiveStep();

        ExecutionRecord finished = run("""
                {
                  "id": "fallback_test", "version": "1.0", "entry": "extract",
                  "steps": [
                    {"id": "extract", "type": "llm", "model": "fast", "prompt": "p.v1",
                     "output": "result", "next": "done",
                     "onFailure": {"strategy": "fallback", "model": "local"}},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, StepExecutors.of(step, new TerminalStepExecutor()));

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertEquals(List.of("fast", "local"), step.modelsTried);
    }

    @Test
    void letsTheFallbackBringItsOwnPrompt() {
        ModelSensitiveStep step = new ModelSensitiveStep();

        run("""
                {
                  "id": "fallback_test", "version": "1.0", "entry": "extract",
                  "steps": [
                    {"id": "extract", "type": "llm", "model": "fast", "prompt": "p.v1",
                     "output": "result", "next": "done",
                     "onFailure": {"strategy": "fallback", "model": "local", "prompt": "p.simple.v1"}},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, StepExecutors.of(step, new TerminalStepExecutor()));

        assertEquals(List.of("p.v1", "p.simple.v1"), step.promptsTried,
                "a small model deserves a prompt written for it");
    }

    @Test
    void recordsBothTheFailureAndTheFallbackInTheHistory() {
        ModelSensitiveStep step = new ModelSensitiveStep();

        ExecutionRecord finished = run("""
                {
                  "id": "fallback_test", "version": "1.0", "entry": "extract",
                  "steps": [
                    {"id": "extract", "type": "llm", "model": "fast", "prompt": "p.v1",
                     "output": "result", "next": "done",
                     "onFailure": {"strategy": "fallback", "model": "local"}},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, StepExecutors.of(step, new TerminalStepExecutor()));

        List<String> outcomes = stateStore.historyOf(finished.executionId()).stream()
                .filter(entry -> entry.stepId().equals(StepId.of("extract")))
                .map(entry -> entry.outcome().name())
                .toList();

        assertEquals(List.of("FAILED", "SUCCESS"), outcomes);
    }

    @Test
    void refusesToRetryACapabilityThatSaidItIsNotIdempotent() {
        AtomicInteger calls = new AtomicInteger();

        InMemoryCapabilityRegistry capabilities = new InMemoryCapabilityRegistry().register(
                new CapabilityDescriptor(
                        CapabilityId.of("take_payment"), "Charge the customer",
                        CapabilityKind.APPLICATION, "billing-team", "1.0", List.of(), null, null,
                        JsonNodeFactory.instance.objectNode()
                                .put("type", "grpc")
                                .put("target", "billing")
                                .put("idempotent", false)));

        StepExecutors executors = StepExecutors.of(
                new CapabilityStepExecutor(capabilities,
                        List.of(new CountingProvider("grpc", calls))),
                new TerminalStepExecutor());

        ExecutionRecord finished = run("""
                {
                  "id": "fallback_test", "version": "1.0", "entry": "charge",
                  "steps": [
                    {"id": "charge", "type": "capability", "capability": "take_payment",
                     "output": "receipt", "next": "done",
                     "retry": {"maxAttempts": 4, "initialDelay": "1ms"}},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, executors);

        assertEquals(ExecutionStatus.FAILED, finished.status());
        assertEquals(1, calls.get(),
                "a timeout leaves it unknown whether the charge landed, so it must not be repeated");
    }

    @Test
    void stillRetriesACapabilityThatDidNotOptOut() {
        AtomicInteger calls = new AtomicInteger();

        InMemoryCapabilityRegistry capabilities = new InMemoryCapabilityRegistry().register(
                new CapabilityDescriptor(
                        CapabilityId.of("search"), "Read-only lookup",
                        CapabilityKind.EXTERNAL, "platform-team", "1.0", List.of(), null, null,
                        JsonNodeFactory.instance.objectNode().put("type", "grpc")));

        StepExecutors executors = StepExecutors.of(
                new CapabilityStepExecutor(capabilities,
                        List.of(new CountingProvider("grpc", calls))),
                new TerminalStepExecutor());

        run("""
                {
                  "id": "fallback_test", "version": "1.0", "entry": "charge",
                  "steps": [
                    {"id": "charge", "type": "capability", "capability": "search",
                     "output": "hits", "next": "done",
                     "retry": {"maxAttempts": 3, "initialDelay": "1ms"}},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, executors);

        assertEquals(3, calls.get());
        assertTrue(calls.get() > 1, "a read is safe to try again");
    }
}
