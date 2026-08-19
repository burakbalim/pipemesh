package io.pipemesh.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.ExecutionHandle;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionSnapshot;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.ResumeSignal;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.WorkflowRuntime;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.ConditionStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.ApprovalRecord;
import io.pipemesh.core.state.StepRecord;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test this whole slice exists for: an execution waiting for a person must
 * survive the process that started it.
 *
 * <p>Each "process" here is a completely fresh object graph — new data source,
 * new stores, new registry, new runtime — sharing nothing but the database. The
 * database is the only thing that crosses the restart, which is the point.
 */
class DurableApprovalRestartTest {

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

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
    }

    @BeforeEach
    void migrate() {
        new SchemaMigrator(dataSource()).migrate();
    }

    @AfterEach
    void clean() throws Exception {
        try (var connection = dataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE workflow_step_history, workflow_approval, workflow_execution");
        }
    }

    private DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }

    /** Everything a single process would hold. Building a second one is the restart. */
    private record Process(WorkflowRuntime runtime,
                           PostgresStateStore stateStore,
                           PostgresApprovalStore approvals) {
    }

    private Process boot() {
        DataSource dataSource = dataSource();
        PostgresStateStore stateStore = new PostgresStateStore(dataSource);
        PostgresApprovalStore approvals = new PostgresApprovalStore(dataSource);

        StepExecutors executors = StepExecutors.of(
                new ConditionStepExecutor(),
                new ApprovalStepExecutor(approvals),
                new TerminalStepExecutor());

        InMemoryWorkflowRegistry registry =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        registry.register(new WorkflowDefinitionReader().read(BOOKING));

        return new Process(
                new DefaultWorkflowRuntime(
                        registry, stateStore, new WorkflowExecutor(stateStore, executors)),
                stateStore,
                approvals);
    }

    private ExecutionInput input(String json) {
        try {
            return new ExecutionInput((ObjectNode) JSON.readTree(json));
        } catch (Exception malformed) {
            throw new IllegalArgumentException(malformed);
        }
    }

    private String approvalIdOf(ExecutionHandle handle) {
        return handle.executionId().value() + ":approval";
    }

    @Test
    void resumesAWaitingExecutionInAProcessThatNeverStartedIt() {
        Process before = boot();
        ExecutionHandle waiting =
                before.runtime().start(WorkflowId.of("venue_booking"), input("{\"price\":250}"));

        assertEquals(ExecutionStatus.WAITING, waiting.status());

        Process after = boot();
        assertNotSame(before.runtime(), after.runtime());

        ExecutionHandle finished = after.runtime().resume(waiting.executionId(),
                new ResumeSignal.Approval(approvalIdOf(waiting), true, "burak", "go ahead"));

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertEquals(StepId.of("booked"), finished.currentStep());
    }

    @Test
    void keepsTheVariablesItWasSuspendedWith() {
        Process before = boot();
        ExecutionHandle waiting =
                before.runtime().start(WorkflowId.of("venue_booking"), input("{\"price\":250}"));

        ExecutionSnapshot snapshot =
                boot().runtime().snapshot(waiting.executionId()).orElseThrow();

        assertEquals(ExecutionStatus.WAITING, snapshot.status());
        assertEquals(250, snapshot.variables().path("input").path("price").asInt());
        assertEquals(StepId.of("approval"), snapshot.currentStepIfAny().orElseThrow());
    }

    @Test
    void keepsThePendingApprovalAcrossTheRestart() {
        Process before = boot();
        ExecutionHandle waiting =
                before.runtime().start(WorkflowId.of("venue_booking"), input("{\"price\":250}"));

        List<ApprovalRecord> pending = boot().approvals().pendingFor(waiting.executionId());

        assertEquals(1, pending.size());
        assertEquals("Book this venue?", pending.get(0).message());
    }

    @Test
    void keepsTheStepHistoryOfBothProcesses() {
        Process before = boot();
        ExecutionHandle waiting =
                before.runtime().start(WorkflowId.of("venue_booking"), input("{\"price\":250}"));

        Process after = boot();
        after.runtime().resume(waiting.executionId(),
                new ResumeSignal.Approval(approvalIdOf(waiting), true, "burak", ""));

        List<StepRecord> history = after.stateStore().historyOf(waiting.executionId());

        assertEquals(
                List.of("check_price", "approval", "approval", "booked"),
                history.stream().map(step -> step.stepId().value()).toList());

        // The approval appears twice: once suspending in the first process, once
        // resuming in the second. Both halves of the wait are on the record.
        assertEquals(StepRecord.StepOutcome.SUCCESS, history.get(0).outcome());
        assertEquals(StepRecord.StepOutcome.SUSPENDED, history.get(1).outcome());
        assertEquals(StepRecord.StepOutcome.SUCCESS, history.get(2).outcome());
        assertEquals(StepRecord.StepOutcome.SUCCESS, history.get(3).outcome());
    }

    @Test
    void appliesTheSameDecisionOnceEvenAcrossProcesses() {
        Process before = boot();
        ExecutionHandle waiting =
                before.runtime().start(WorkflowId.of("venue_booking"), input("{\"price\":250}"));
        ResumeSignal signal =
                new ResumeSignal.Approval(approvalIdOf(waiting), true, "burak", "");

        ExecutionHandle first = boot().runtime().resume(waiting.executionId(), signal);
        int stepsAfterFirst = before.stateStore().historyOf(waiting.executionId()).size();

        ExecutionHandle second = boot().runtime().resume(waiting.executionId(), signal);

        assertEquals(ExecutionStatus.COMPLETED, first.status());
        assertEquals(ExecutionStatus.COMPLETED, second.status());
        assertEquals(stepsAfterFirst, before.stateStore().historyOf(waiting.executionId()).size());
    }

    @Test
    void rejectsAWriteMadeFromStaleState() {
        Process process = boot();
        ExecutionHandle waiting =
                process.runtime().start(WorkflowId.of("venue_booking"), input("{\"price\":250}"));

        var stale = process.stateStore().find(waiting.executionId()).orElseThrow();
        process.runtime().resume(waiting.executionId(),
                new ResumeSignal.Approval(approvalIdOf(waiting), true, "burak", ""));

        assertThrowsStale(process, stale);
    }

    private void assertThrowsStale(Process process, io.pipemesh.core.state.ExecutionRecord stale) {
        boolean rejected;
        try {
            process.stateStore().advance(stale, new StepRecord(
                    stale.executionId(), StepId.of("approval"),
                    io.pipemesh.core.workflow.StepType.HUMAN_APPROVAL,
                    StepRecord.StepOutcome.SUCCESS, null, null, "", "", 0, 0, 0, 1, 1, null));
            rejected = false;
        } catch (io.pipemesh.core.state.StaleExecutionException expected) {
            rejected = true;
        }
        assertTrue(rejected, "a write from a stale version must not be applied");
    }
}
