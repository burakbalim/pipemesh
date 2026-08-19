package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.ConditionStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.ApprovalRecord;
import io.pipemesh.core.state.StepRecord;
import io.pipemesh.core.state.memory.InMemoryApprovalStore;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalResumeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BOOKING = """
            {
              "id": "venue_booking", "version": "1.0", "entry": "check_price",
              "steps": [
                {"id": "check_price", "type": "condition", "expression": "$.input.price > 100",
                 "onTrue": "approval", "onFalse": "booked"},
                {"id": "approval", "type": "human_approval", "message": "Book this venue?",
                 "onApproved": "booked", "onRejected": "cancelled"},
                {"id": "booked", "type": "terminal", "status": "COMPLETED"},
                {"id": "cancelled", "type": "terminal", "status": "CANCELLED"}
              ]
            }
            """;

    private final InMemoryStateStore stateStore = new InMemoryStateStore();
    private final InMemoryApprovalStore approvals = new InMemoryApprovalStore();

    private final StepExecutors executors = StepExecutors.of(
            new ConditionStepExecutor(),
            new ApprovalStepExecutor(approvals),
            new TerminalStepExecutor());

    private final InMemoryWorkflowRegistry registry =
            new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));

    private final WorkflowRuntime runtime = new DefaultWorkflowRuntime(
            registry, stateStore, new WorkflowExecutor(stateStore, executors));

    @BeforeEach
    void registerWorkflow() {
        registry.register(new WorkflowDefinitionReader().read(BOOKING));
    }

    private ExecutionInput input(String json) {
        try {
            return new ExecutionInput((ObjectNode) JSON.readTree(json));
        } catch (Exception malformed) {
            throw new IllegalArgumentException(malformed);
        }
    }

    private ExecutionHandle startExpensiveBooking() {
        return runtime.start(ExecutionRequest.of(
                WorkflowId.of("venue_booking"), input("{\"price\":250}")));
    }

    private String approvalIdOf(ExecutionHandle handle) {
        return handle.executionId().value() + ":approval";
    }

    @Test
    void suspendsAtTheApprovalWithoutHoldingAnything() {
        ExecutionHandle handle = startExpensiveBooking();

        assertEquals(ExecutionStatus.WAITING, handle.status());
        assertEquals(StepId.of("approval"), handle.currentStep());
    }

    @Test
    void registersAPendingApprovalToDecide() {
        ExecutionHandle handle = startExpensiveBooking();

        List<ApprovalRecord> pending = approvals.pendingFor(handle.executionId());

        assertEquals(1, pending.size());
        assertEquals("Book this venue?", pending.get(0).message());
    }

    @Test
    void continuesToTheApprovedBranch() {
        ExecutionHandle waiting = startExpensiveBooking();

        ExecutionHandle finished = runtime.resume(waiting.executionId(),
                new ResumeSignal.Approval(approvalIdOf(waiting), true, "burak", "looks good"));

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertEquals(StepId.of("booked"), finished.currentStep());
    }

    @Test
    void continuesToTheRejectedBranchWithoutRunningTheApprovedPath() {
        ExecutionHandle waiting = startExpensiveBooking();

        ExecutionHandle finished = runtime.resume(waiting.executionId(),
                new ResumeSignal.Approval(approvalIdOf(waiting), false, "burak", "too expensive"));

        assertEquals(ExecutionStatus.CANCELLED, finished.status());
        assertTrue(stateStore.historyOf(finished.executionId()).stream()
                .noneMatch(step -> step.stepId().equals(StepId.of("booked"))));
    }

    @Test
    void recordsWhoDecidedAndWhen() {
        ExecutionHandle waiting = startExpensiveBooking();
        String approvalId = approvalIdOf(waiting);

        runtime.resume(waiting.executionId(),
                new ResumeSignal.Approval(approvalId, true, "burak", "looks good"));

        ApprovalRecord settled = approvals.find(approvalId).orElseThrow();
        assertEquals(ApprovalRecord.ApprovalStatus.APPROVED, settled.status());
        assertEquals("burak", settled.decidedBy());
        assertTrue(settled.decidedAtEpochMillis() > 0);
    }

    @Test
    void ignoresTheSameDecisionDeliveredTwice() {
        ExecutionHandle waiting = startExpensiveBooking();
        ResumeSignal signal =
                new ResumeSignal.Approval(approvalIdOf(waiting), true, "burak", "");

        ExecutionHandle first = runtime.resume(waiting.executionId(), signal);
        int stepsAfterFirst = stateStore.historyOf(waiting.executionId()).size();

        ExecutionHandle second = runtime.resume(waiting.executionId(), signal);

        assertEquals(first.status(), second.status());
        assertEquals(StepId.of("booked"), second.currentStep());
        assertEquals(stepsAfterFirst, stateStore.historyOf(waiting.executionId()).size());
    }

    @Test
    void rejectsADecisionForAnotherApproval() {
        ExecutionHandle waiting = startExpensiveBooking();

        ExecutionHandle handle = runtime.resume(waiting.executionId(),
                new ResumeSignal.Approval("someone-elses-approval", true, "burak", ""));

        assertEquals(ExecutionStatus.FAILED, handle.status());

        List<StepRecord> history = stateStore.historyOf(waiting.executionId());
        assertEquals("approval.unknown_signal",
                history.get(history.size() - 1).output().path("code").asText());
    }

    @Test
    void skipsTheApprovalEntirelyWhenTheConditionDoesNotAskForOne() {
        ExecutionHandle handle =
                runtime.start(ExecutionRequest.of(
                        WorkflowId.of("venue_booking"), input("{\"price\":10}")));

        assertEquals(ExecutionStatus.COMPLETED, handle.status());
        assertTrue(approvals.pendingFor(handle.executionId()).isEmpty());
    }

    @Test
    void reportsWhereAnExecutionStandsWhileItWaits() {
        ExecutionHandle waiting = startExpensiveBooking();

        ExecutionSnapshot snapshot = runtime.snapshot(waiting.executionId()).orElseThrow();

        assertEquals(ExecutionStatus.WAITING, snapshot.status());
        assertEquals(250, snapshot.variables().path("input").path("price").asInt());
        assertTrue(snapshot.createdAtEpochMillis() > 0);
    }
}
