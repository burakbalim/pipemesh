package io.pipemesh.core.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.ExecutionHandle;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.execution.ResumeSignal;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.WorkflowRuntime;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.ConditionStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.memory.InMemoryApprovalStore;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionObserverTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BOOKING = """
            {
              "id": "venue_booking", "version": "1.0", "entry": "check_price",
              "steps": [
                {"id": "check_price", "type": "condition", "expression": "$.input.price > 100",
                 "onTrue": "approval", "onFalse": "booked"},
                {"id": "approval", "type": "human_approval", "message": "Book?",
                 "onApproved": "booked", "onRejected": "cancelled"},
                {"id": "booked", "type": "terminal", "status": "COMPLETED"},
                {"id": "cancelled", "type": "terminal", "status": "CANCELLED"}
              ]
            }
            """;

    /** Records what it was told, the way a real exporter would forward it. */
    private static final class Recorder implements ExecutionObserver {

        final List<String> events = new ArrayList<>();
        final List<ExecutionEvent> executions = new ArrayList<>();
        final List<StepEvent> steps = new ArrayList<>();

        @Override
        public void executionStarted(ExecutionEvent event) {
            events.add("started");
            executions.add(event);
        }

        @Override
        public void stepFinished(StepEvent event) {
            events.add("step:" + event.stepId().value());
            steps.add(event);
        }

        @Override
        public void executionSuspended(ExecutionEvent event) {
            events.add("suspended");
            executions.add(event);
        }

        @Override
        public void executionResumed(ExecutionEvent event) {
            events.add("resumed");
            executions.add(event);
        }

        @Override
        public void executionFinished(ExecutionEvent event) {
            events.add("finished");
            executions.add(event);
        }
    }

    private static final class BrokenObserver implements ExecutionObserver {

        @Override
        public void executionStarted(ExecutionEvent event) {
            throw new IllegalStateException("exporter is down");
        }

        @Override
        public void stepFinished(StepEvent event) {
            throw new IllegalStateException("exporter is down");
        }
    }

    private final InMemoryStateStore stateStore = new InMemoryStateStore();
    private final InMemoryApprovalStore approvals = new InMemoryApprovalStore();

    private WorkflowRuntime runtimeWith(ExecutionObserver observer) {
        StepExecutors executors = StepExecutors.of(
                new ConditionStepExecutor(),
                new ApprovalStepExecutor(approvals),
                new TerminalStepExecutor());

        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        workflows.register(new WorkflowDefinitionReader().read(BOOKING));

        return new DefaultWorkflowRuntime(
                workflows, stateStore, new WorkflowExecutor(stateStore, executors, observer));
    }

    private ExecutionInput input(String json) {
        try {
            return new ExecutionInput((ObjectNode) JSON.readTree(json));
        } catch (Exception malformed) {
            throw new IllegalArgumentException(malformed);
        }
    }

    private ExecutionRequest expensive(OrganizationId organization) {
        return ExecutionRequest.of(
                WorkflowId.of("venue_booking"), input("{\"price\":250}"), organization);
    }

    @Test
    void reportsTheLifeOfAnExecutionInOrder() {
        Recorder recorder = new Recorder();
        WorkflowRuntime runtime = runtimeWith(recorder);

        ExecutionHandle waiting = runtime.start(expensive(OrganizationId.DEFAULT));
        runtime.resume(waiting.executionId(), new ResumeSignal.Approval(
                waiting.executionId().value() + ":approval", true, "burak", ""));

        assertEquals(
                List.of("started", "step:check_price", "step:approval", "suspended",
                        "resumed", "step:approval", "step:booked", "finished"),
                recorder.events);
    }

    @Test
    void labelsEveryEventWithTheOrganizationItBelongsTo() {
        Recorder recorder = new Recorder();
        runtimeWith(recorder).start(expensive(OrganizationId.of("acme")));

        assertTrue(recorder.executions.stream()
                .allMatch(event -> event.organization().value().equals("acme")));
        assertEquals("acme",
                recorder.steps.get(0).attributes().get(TelemetryAttributes.ORGANIZATION));
    }

    @Test
    void keepsOneTraceAcrossTheWaitAndTheResume() {
        Recorder recorder = new Recorder();
        WorkflowRuntime runtime = runtimeWith(recorder);

        ExecutionHandle waiting = runtime.start(expensive(OrganizationId.DEFAULT));
        runtime.resume(waiting.executionId(), new ResumeSignal.Approval(
                waiting.executionId().value() + ":approval", true, "burak", ""));

        List<String> traceIds = recorder.executions.stream()
                .map(event -> event.traceIfAny().orElseThrow().traceId())
                .distinct()
                .toList();

        assertEquals(1, traceIds.size(), "a suspended execution must resume into its own trace");
    }

    @Test
    void hangsUnderTheTraceTheCallerWasAlreadyIn() {
        Recorder recorder = new Recorder();
        String callerTrace = "0af7651916cd43dd8448eb211c80319c";

        runtimeWith(recorder).start(expensive(OrganizationId.DEFAULT)
                .within("00-" + callerTrace + "-b7ad6b7169203331-01"));

        assertEquals(callerTrace, recorder.executions.get(0).traceIfAny().orElseThrow().traceId());
    }

    @Test
    void startsItsOwnTraceWhenTheCallerHasNone() {
        Recorder first = new Recorder();
        Recorder second = new Recorder();

        runtimeWith(first).start(expensive(OrganizationId.DEFAULT));
        runtimeWith(second).start(expensive(OrganizationId.DEFAULT));

        assertNotEquals(
                first.executions.get(0).traceIfAny().orElseThrow().traceId(),
                second.executions.get(0).traceIfAny().orElseThrow().traceId());
    }

    @Test
    void carriesWhatAStepReportedThroughToTheBackend() {
        Recorder recorder = new Recorder();
        runtimeWith(recorder).start(expensive(OrganizationId.DEFAULT));

        StepEvent step = recorder.steps.get(0);
        assertEquals("condition", step.attributes().get(TelemetryAttributes.STEP_TYPE));
        assertEquals("SUCCESS", step.attributes().get(TelemetryAttributes.STEP_OUTCOME));
    }

    @Test
    void aBrokenExporterDoesNotTakeTheWorkflowDown() {
        ExecutionHandle handle = runtimeWith(new BrokenObserver()).start(expensive(OrganizationId.DEFAULT));

        assertEquals(ExecutionStatus.WAITING, handle.status());
    }

    @Test
    void oneBrokenExporterDoesNotSilenceTheOthers() {
        Recorder healthy = new Recorder();
        List<Throwable> failures = new ArrayList<>();

        ExecutionObserver both = new CompositeExecutionObserver(
                List.of(new BrokenObserver(), healthy), failures::add);

        runtimeWith(both).start(expensive(OrganizationId.DEFAULT));

        assertTrue(healthy.events.contains("started"));
        assertFalse(failures.isEmpty(), "a failing exporter should be reported, not swallowed");
    }
}
