package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.pipemesh.core.capability.CapabilityCall;
import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityId;
import io.pipemesh.core.capability.CapabilityKind;
import io.pipemesh.core.capability.CapabilityProvider;
import io.pipemesh.core.capability.CapabilityResult;
import io.pipemesh.core.capability.InMemoryCapabilityRegistry;
import io.pipemesh.core.execution.step.CapabilityStepExecutor;
import io.pipemesh.core.execution.step.ConditionStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.observability.ExecutionEvent;
import io.pipemesh.core.observability.ExecutionObserver;
import io.pipemesh.core.observability.RecoveryEvent;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.state.StepRecord;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import io.pipemesh.core.workflow.WorkflowVersion;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recovery is what turns "the process died" from a permanently half-finished
 * execution into a resumed one.
 */
class RecoverySweeperTest {

    private static final String WORKFLOW = """
            {
              "id": "sweep_test", "version": "1.0", "entry": "call",
              "steps": [
                {"id": "call", "type": "capability", "capability": "lookup",
                 "output": "hits", "next": "done"},
                {"id": "done", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    private record AlwaysSucceeds(String type) implements CapabilityProvider {

        @Override
        public CapabilityResult invoke(CapabilityDescriptor capability,
                                       com.fasterxml.jackson.databind.JsonNode input,
                                       CapabilityCall call) {
            return new CapabilityResult.Success(JsonNodeFactory.instance.objectNode().put("found", 1));
        }
    }

    private static final class RecoveryRecorder implements ExecutionObserver {

        final List<String> recovered = new ArrayList<>();
        final List<Boolean> repeated = new ArrayList<>();

        @Override
        public void executionRecovered(RecoveryEvent event) {
            recovered.add(event.execution().executionId().value());
            repeated.add(event.repeated());
        }
    }

    private final Instant now = Instant.parse("2026-08-20T12:00:00Z");
    private final TestClock clock = new TestClock(now);
    private final InMemoryStateStore stateStore = new InMemoryStateStore(clock);

    private InMemoryCapabilityRegistry capabilities(boolean idempotent) {
        var execution = JsonNodeFactory.instance.objectNode().put("type", "grpc");
        if (!idempotent) {
            execution.put("idempotent", false);
        }
        return new InMemoryCapabilityRegistry().register(new CapabilityDescriptor(
                CapabilityId.of("lookup"), "", CapabilityKind.EXTERNAL, "team", "1.0",
                List.of(), null, null, execution));
    }

    private StepExecutors executors(boolean idempotent) {
        return StepExecutors.of(
                new CapabilityStepExecutor(capabilities(idempotent),
                        List.of(new AlwaysSucceeds("grpc"))),
                new ConditionStepExecutor(),
                new TerminalStepExecutor());
    }

    private InMemoryWorkflowRegistry workflows(StepExecutors executors) {
        InMemoryWorkflowRegistry registry =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        registry.register(new WorkflowDefinitionReader().read(WORKFLOW));
        return registry;
    }

    /**
     * Writes the row a killed process would have left behind: created while the
     * clock said {@code lastTouched}, then nothing.
     */
    private ExecutionRecord orphanFrom(Instant lastTouched, ExecutionStatus status) {
        clock.set(lastTouched);
        ExecutionRecord orphan = stateStore.create(new ExecutionRecord(
                ExecutionId.generate(), OrganizationId.of("acme"),
                WorkflowId.of("sweep_test"), WorkflowVersion.of("1.0"),
                status, StepId.of("call"), JsonNodeFactory.instance.objectNode(),
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", 0L, 0L, 0L));
        clock.set(now);
        return orphan;
    }

    private ExecutionRecord orphanFrom(Instant lastTouched) {
        return orphanFrom(lastTouched, ExecutionStatus.RUNNING);
    }

    private RecoverySweeper sweeper(StepExecutors executors, ExecutionObserver observer) {
        return new RecoverySweeper(
                workflows(executors), stateStore,
                new WorkflowExecutor(stateStore, executors, clock,
                        WorkflowExecutor.DEFAULT_STEP_BUDGET, observer),
                executors, clock, Duration.ofMinutes(5), 50, observer);
    }

    @Test
    void picksUpAnExecutionLeftRunningAndFinishesIt() {
        ExecutionRecord orphan = orphanFrom(now.minus(Duration.ofMinutes(30)));

        int recovered = sweeper(executors(true), ExecutionObserver.NONE).sweep();

        assertEquals(1, recovered);
        assertEquals(ExecutionStatus.COMPLETED,
                stateStore.find(orphan.executionId()).orElseThrow().status());
    }

    @Test
    void leavesAnExecutionThatWasTouchedRecently() {
        orphanFrom(now.minus(Duration.ofSeconds(30)));

        assertEquals(0, sweeper(executors(true), ExecutionObserver.NONE).sweep(),
                "a step that started half a minute ago is probably still running");
    }

    @Test
    void stopsAnExecutionStuckOnAStepThatCannotBeRepeated() {
        ExecutionRecord orphan = orphanFrom(now.minus(Duration.ofMinutes(30)));

        sweeper(executors(false), ExecutionObserver.NONE).sweep();

        ExecutionRecord after = stateStore.find(orphan.executionId()).orElseThrow();
        assertEquals(ExecutionStatus.FAILED, after.status());

        List<StepRecord> history = stateStore.historyOf(orphan.executionId());
        assertEquals("execution.unrecoverable",
                history.get(history.size() - 1).output().path("code").asText());
    }

    @Test
    void reportsWhatItPickedUp() {
        ExecutionRecord orphan = orphanFrom(now.minus(Duration.ofMinutes(30)));
        RecoveryRecorder recorder = new RecoveryRecorder();

        sweeper(executors(true), recorder).sweep();

        assertEquals(List.of(orphan.executionId().value()), recorder.recovered);
        assertEquals(List.of(true), recorder.repeated, "it carried on");
    }

    /**
     * A recovery that gives up is still a recovery. Saying nothing here would
     * make the one case somebody needs to know about the quietest one.
     */
    @Test
    void reportsARecoveryThatCouldNotCarryOn() {
        ExecutionRecord orphan = orphanFrom(now.minus(Duration.ofMinutes(30)));
        RecoveryRecorder recorder = new RecoveryRecorder();

        sweeper(executors(false), recorder).sweep();

        assertEquals(List.of(orphan.executionId().value()), recorder.recovered);
        assertEquals(List.of(false), recorder.repeated, "the step could not be repeated");
    }

    @Test
    void doesNotTouchExecutionsThatAreWaitingOrFinished() {
        ExecutionRecord waiting =
                orphanFrom(now.minus(Duration.ofDays(3)), ExecutionStatus.WAITING);

        assertEquals(0, sweeper(executors(true), ExecutionObserver.NONE).sweep(),
                "waiting three days for a person is not being stuck");
        assertEquals(ExecutionStatus.WAITING,
                stateStore.find(waiting.executionId()).orElseThrow().status());
    }

    @Test
    void takesAtMostOneBatchPerPass() {
        for (int i = 0; i < 5; i++) {
            orphanFrom(now.minus(Duration.ofMinutes(30)));
        }

        StepExecutors executors = executors(true);
        RecoverySweeper bounded = new RecoverySweeper(
                workflows(executors), stateStore,
                new WorkflowExecutor(stateStore, executors, clock,
                        WorkflowExecutor.DEFAULT_STEP_BUDGET, ExecutionObserver.NONE),
                executors, clock, Duration.ofMinutes(5), 2, ExecutionObserver.NONE);

        assertEquals(2, bounded.sweep());
        assertEquals(2, bounded.sweep());
        assertEquals(1, bounded.sweep());
    }

    @Test
    void keepsTheExecutionInItsOriginalTrace() {
        ExecutionRecord orphan = orphanFrom(now.minus(Duration.ofMinutes(30)));
        String traceBefore = orphan.traceContext();

        sweeper(executors(true), ExecutionObserver.NONE).sweep();

        assertEquals(traceBefore,
                stateStore.find(orphan.executionId()).orElseThrow().traceContext());
        assertTrue(traceBefore.startsWith("00-"));
    }
}
