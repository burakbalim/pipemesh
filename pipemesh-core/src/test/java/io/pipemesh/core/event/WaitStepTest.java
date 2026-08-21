package io.pipemesh.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.ExecutionHandle;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.execution.RecoveryScheduler;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.execution.step.WaitStepExecutor;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.state.memory.InMemoryWaitStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Waiting was easy — approval brought that. Being findable is the new part
 * (§9.7).
 */
class WaitStepTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SHIPPING = """
            {
              "id": "shipping", "version": "1.0", "entry": "await_payment",
              "steps": [
                {"id": "await_payment", "type": "wait", "event": "payment_completed",
                 "correlationKey": "$.input.order", "output": "payment", "next": "ship"},
                {"id": "ship", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    private final Instant now = Instant.parse("2026-08-21T09:00:00Z");
    private final InMemoryStateStore stateStore = new InMemoryStateStore();
    private final InMemoryWaitStore waits = new InMemoryWaitStore();

    private DefaultWorkflowRuntime runtime(String workflow, Clock clock) {
        StepExecutors executors = StepExecutors.of(
                new WaitStepExecutor(waits, clock), new TerminalStepExecutor());

        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        workflows.register(new WorkflowDefinitionReader().read(workflow));

        return new DefaultWorkflowRuntime(
                workflows, stateStore, new WorkflowExecutor(stateStore, executors));
    }

    private DefaultWorkflowRuntime runtime() {
        return runtime(SHIPPING, Clock.fixed(now, ZoneOffset.UTC));
    }

    private ExecutionHandle start(DefaultWorkflowRuntime runtime, String order, String organization) {
        try {
            return runtime.start(new ExecutionRequest(
                    WorkflowId.of("shipping"),
                    new ExecutionInput((ObjectNode) JSON.readTree("{\"order\":\"" + order + "\"}")),
                    OrganizationId.of(organization), null));
        } catch (Exception malformed) {
            throw new IllegalStateException(malformed);
        }
    }

    private EventKey key(String organization, String order) {
        return new EventKey(OrganizationId.of(organization), "payment_completed", order);
    }

    private ObjectNode paid(int amount) {
        return JsonNodeFactory.instance.objectNode().put("amount", amount);
    }

    @Test
    void stopsToWaitWithoutHoldingAnything() {
        ExecutionHandle waiting = start(runtime(), "A-4172", "acme");

        assertEquals(ExecutionStatus.WAITING, waiting.status());
        assertEquals(1, waits.waitingFor(key("acme", "A-4172")).size());
    }

    @Test
    void anEventFindsTheExecutionFiledUnderIt() {
        DefaultWorkflowRuntime runtime = runtime();
        ExecutionHandle waiting = start(runtime, "A-4172", "acme");

        List<ExecutionHandle> moved =
                new EventPublisher(waits, runtime).publish(key("acme", "A-4172"), paid(90));

        assertEquals(1, moved.size());
        assertEquals(ExecutionStatus.COMPLETED, moved.get(0).status());
        assertEquals(90, runtime.snapshot(waiting.executionId()).orElseThrow()
                .variables().path("payment").path("amount").asInt());
    }

    @Test
    void anEventAboutSomethingElseFindsNobody() {
        DefaultWorkflowRuntime runtime = runtime();
        ExecutionHandle waiting = start(runtime, "A-4172", "acme");

        assertTrue(new EventPublisher(waits, runtime)
                .publish(key("acme", "B-9999"), paid(90)).isEmpty());

        assertEquals(ExecutionStatus.WAITING,
                runtime.snapshot(waiting.executionId()).orElseThrow().status());
    }

    @Test
    void oneOrganizationsEventDoesNotMoveAnothersExecution() {
        DefaultWorkflowRuntime runtime = runtime();
        ExecutionHandle acme = start(runtime, "A-4172", "acme");

        assertTrue(new EventPublisher(waits, runtime)
                        .publish(key("rival", "A-4172"), paid(90)).isEmpty(),
                "an isolation boundary with an exception for events is not one");

        assertEquals(ExecutionStatus.WAITING,
                runtime.snapshot(acme.executionId()).orElseThrow().status());
    }

    @Test
    void everyExecutionWaitingForItHearsAboutIt() {
        DefaultWorkflowRuntime runtime = runtime();
        start(runtime, "A-4172", "acme");
        start(runtime, "A-4172", "acme");

        List<ExecutionHandle> moved =
                new EventPublisher(waits, runtime).publish(key("acme", "A-4172"), paid(90));

        assertEquals(2, moved.size(), "stopping at the first would abandon the second in silence");
        assertTrue(moved.stream().allMatch(handle -> handle.status() == ExecutionStatus.COMPLETED));
    }

    @Test
    void theSameEventTwiceMovesAnExecutionOnce() {
        DefaultWorkflowRuntime runtime = runtime();
        ExecutionHandle waiting = start(runtime, "A-4172", "acme");
        EventPublisher publisher = new EventPublisher(waits, runtime);

        publisher.publish(key("acme", "A-4172"), paid(90));
        int stepsAfterFirst = stateStore.historyOf(waiting.executionId()).size();

        assertTrue(publisher.publish(key("acme", "A-4172"), paid(90)).isEmpty());
        assertEquals(stepsAfterFirst, stateStore.historyOf(waiting.executionId()).size());
    }

    @Test
    void refusesAWaitNothingCouldEverFind() {
        DefaultWorkflowRuntime runtime = runtime("""
                {
                  "id": "shipping", "version": "1.0", "entry": "await_payment",
                  "steps": [
                    {"id": "await_payment", "type": "wait", "event": "payment_completed",
                     "correlationKey": "$.input.missing", "next": "ship"},
                    {"id": "ship", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, Clock.fixed(now, ZoneOffset.UTC));

        ExecutionHandle handle = start(runtime, "A-4172", "acme");

        assertEquals(ExecutionStatus.FAILED, handle.status(),
                "a wait filed under nothing is an execution stopped forever with no error");
    }

    @Test
    void aWaitThatRanOutTakesTheTimeoutPath() {
        var clock = new io.pipemesh.core.execution.TestClock(now);
        DefaultWorkflowRuntime runtime = runtime("""
                {
                  "id": "shipping", "version": "1.0", "entry": "await_payment",
                  "steps": [
                    {"id": "await_payment", "type": "wait", "event": "payment_completed",
                     "correlationKey": "$.input.order", "next": "ship",
                     "timeoutSeconds": 60, "onTimeout": "chased"},
                    {"id": "ship", "type": "terminal", "status": "COMPLETED"},
                    {"id": "chased", "type": "terminal", "status": "CANCELLED"}
                  ]
                }
                """, clock);

        ExecutionHandle waiting = start(runtime, "A-4172", "acme");
        clock.set(now.plusSeconds(120));

        int swept = new ExpiredWaitSweeper(waits, runtime, clock, 50).sweep();

        assertEquals(1, swept);
        assertEquals(ExecutionStatus.CANCELLED,
                runtime.snapshot(waiting.executionId()).orElseThrow().status());
    }

    @Test
    void aWaitThatRanOutWithNowhereToGoFails() {
        var clock = new io.pipemesh.core.execution.TestClock(now);
        DefaultWorkflowRuntime runtime = runtime("""
                {
                  "id": "shipping", "version": "1.0", "entry": "await_payment",
                  "steps": [
                    {"id": "await_payment", "type": "wait", "event": "payment_completed",
                     "correlationKey": "$.input.order", "next": "ship", "timeoutSeconds": 60},
                    {"id": "ship", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, clock);

        ExecutionHandle waiting = start(runtime, "A-4172", "acme");
        clock.set(now.plusSeconds(120));
        new ExpiredWaitSweeper(waits, runtime, clock, 50).sweep();

        assertEquals(ExecutionStatus.FAILED,
                runtime.snapshot(waiting.executionId()).orElseThrow().status());
    }

    @Test
    void aWaitWithNoDeadlineIsNotSweptAway() {
        var clock = new io.pipemesh.core.execution.TestClock(now);
        DefaultWorkflowRuntime runtime = runtime(SHIPPING, clock);
        ExecutionHandle waiting = start(runtime, "A-4172", "acme");

        clock.set(now.plusSeconds(60 * 60 * 24 * 30));

        assertEquals(0, new ExpiredWaitSweeper(waits, runtime, clock, 50).sweep());
        assertEquals(ExecutionStatus.WAITING,
                runtime.snapshot(waiting.executionId()).orElseThrow().status());
    }

    @Test
    void theSweeperFitsTheScheduleThatAlreadyRuns() {
        DefaultWorkflowRuntime runtime = runtime();
        RecoveryScheduler.RecoveryPass pass = new ExpiredWaitSweeper(waits, runtime);

        assertEquals(0, pass.sweep(), "a second timer would be a second thing to remember to start");
    }
}
