package io.pipemesh.core.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.CapabilityStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.ExecutionGraph;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "A workflow should not automatically gain access to every registered
 * capability" (§23) — which, until this contract, is exactly what happened.
 */
class CapabilityPermissionTest {

    private static final String WORKFLOW = """
            {
              "id": "refund_flow", "version": "1.0", "entry": "refund",
              "steps": [
                {"id": "refund", "type": "capability", "capability": "refund_payment",
                 "output": "receipt", "next": "done",
                 "retry": {"maxAttempts": 3, "initialDelay": "1ms"}},
                {"id": "done", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    private record CountingProvider(String type, AtomicInteger calls) implements CapabilityProvider {

        @Override
        public CapabilityResult invoke(CapabilityDescriptor capability, JsonNode input, CapabilityCall call) {
            calls.incrementAndGet();
            return new CapabilityResult.Success(JsonNodeFactory.instance.objectNode().put("ok", true));
        }
    }

    private final InMemoryStateStore stateStore = new InMemoryStateStore();
    private final AtomicInteger calls = new AtomicInteger();

    private ExecutionRecord runAs(Principal principal, List<String> required) {
        InMemoryCapabilityRegistry capabilities = new InMemoryCapabilityRegistry().register(
                new CapabilityDescriptor(
                        CapabilityId.of("refund_payment"), "Refund a payment",
                        CapabilityKind.APPLICATION, "billing-team", "1.0",
                        required, null, null,
                        JsonNodeFactory.instance.objectNode().put("type", "grpc")));

        StepExecutors executors = StepExecutors.of(
                new CapabilityStepExecutor(capabilities, List.of(new CountingProvider("grpc", calls))),
                new TerminalStepExecutor());

        ExecutionGraph graph = new InMemoryWorkflowRegistry(new WorkflowCompiler(executors))
                .register(new WorkflowDefinitionReader().read(WORKFLOW));

        return new WorkflowExecutor(stateStore, executors).start(
                graph, ExecutionId.generate(),
                new ExecutionRequest(WorkflowId.of("refund_flow"), ExecutionInput.empty(),
                        null, null, null, principal));
    }

    private String failureCode(ExecutionRecord record) {
        return stateStore.historyOf(record.executionId()).get(0).output().path("code").asText();
    }

    @Test
    void aCapabilityAskingForNothingIsOpenToAnyone() {
        ExecutionRecord finished = runAs(Principal.ANONYMOUS, List.of());

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertEquals(1, calls.get());
    }

    @Test
    void refusesACallerWhoDoesNotHoldWhatItAsksFor() {
        ExecutionRecord finished = runAs(Principal.of("clerk", "billing.read"),
                List.of("billing.refund"));

        assertEquals(ExecutionStatus.FAILED, finished.status());
        assertEquals("capability.forbidden", failureCode(finished));
        assertEquals(0, calls.get(), "a refused call must not reach the provider");
    }

    @Test
    void saysWhichPermissionWasMissing() {
        ExecutionRecord finished = runAs(Principal.of("clerk"), List.of("billing.refund"));

        String message = stateStore.historyOf(finished.executionId())
                .get(0).output().path("message").asText();

        assertTrue(message.contains("billing.refund"), message);
        assertTrue(message.contains("clerk"), message);
    }

    @Test
    void doesNotRetryARefusal() {
        ExecutionRecord finished = runAs(Principal.of("clerk"), List.of("billing.refund"));

        assertEquals(1, stateStore.historyOf(finished.executionId()).size(),
                "trying again with the same caller changes nothing");
    }

    @Test
    void letsACallerThroughWhenItHoldsEverythingAsked() {
        ExecutionRecord finished = runAs(
                Principal.of("manager", "billing.refund", "billing.read"),
                List.of("billing.refund"));

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
    }

    @Test
    void wantsAllOfThemNotJustOne() {
        ExecutionRecord finished = runAs(
                Principal.of("clerk", "billing.refund"),
                List.of("billing.refund", "billing.approve"));

        assertEquals(ExecutionStatus.FAILED, finished.status());
    }

    @Test
    void theSystemCallerHoldsEverything() {
        ExecutionRecord finished = runAs(Principal.SYSTEM, List.of("billing.refund", "anything.else"));

        assertEquals(ExecutionStatus.COMPLETED, finished.status(),
                "code in this process built the runtime; there is nothing to withhold from it");
    }

    @Test
    void anAnonymousCallerHoldsNothing() {
        assertTrue(Principal.ANONYMOUS.missingFrom(List.of("anything")).contains("anything"));
        assertTrue(Principal.ANONYMOUS.permissions().isEmpty());
    }
}
