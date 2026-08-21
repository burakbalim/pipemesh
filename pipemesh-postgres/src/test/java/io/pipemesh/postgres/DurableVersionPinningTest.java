package io.pipemesh.postgres;

import io.pipemesh.core.capability.Principal;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.ExecutionHandle;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.execution.ResumeSignal;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import io.pipemesh.core.workflow.WorkflowVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A deploy is the ordinary case of "the definition changed while somebody was
 * still deciding" (§24). The second process here knows both versions; the
 * execution still has to come back to the one it left in.
 */
class DurableVersionPinningTest {

    private static final String REVIEW_V1 = """
            {
              "id": "review", "version": "1.0", "entry": "decide",
              "steps": [
                {"id": "decide", "type": "human_approval", "message": "ok?",
                 "onApproved": "publish_v1", "onRejected": "drop"},
                {"id": "publish_v1", "type": "terminal", "status": "COMPLETED"},
                {"id": "drop", "type": "terminal", "status": "CANCELLED"}
              ]
            }
            """;

    /** Approving on v2 cancels, so the final status says which graph ran. */
    private static final String REVIEW_V2 = """
            {
              "id": "review", "version": "2.0", "entry": "decide",
              "steps": [
                {"id": "decide", "type": "human_approval", "message": "still ok?",
                 "onApproved": "publish_v2", "onRejected": "drop"},
                {"id": "publish_v2", "type": "terminal", "status": "CANCELLED"},
                {"id": "drop", "type": "terminal", "status": "CANCELLED"}
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
    void clean() {
        TestTables.empty(dataSource());
    }

    private DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }

    private record Process(DefaultWorkflowRuntime runtime, PostgresApprovalStore approvals) {
    }

    /** A process that knows exactly the versions it was deployed with. */
    private Process boot(String... definitions) {
        DataSource dataSource = dataSource();
        PostgresStateStore stateStore = new PostgresStateStore(dataSource);
        PostgresApprovalStore approvals = new PostgresApprovalStore(dataSource);

        StepExecutors executors = StepExecutors.of(
                new ApprovalStepExecutor(approvals), new TerminalStepExecutor());

        InMemoryWorkflowRegistry registry =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        for (String definition : definitions) {
            registry.register(new WorkflowDefinitionReader().read(definition));
        }

        return new Process(
                new DefaultWorkflowRuntime(
                        registry, stateStore, new WorkflowExecutor(stateStore, executors)),
                approvals);
    }

    private ExecutionHandle startReview(Process process) {
        return process.runtime().start(new ExecutionRequest(
                WorkflowId.of("review"), ExecutionInput.empty(),
                OrganizationId.of("acme"), null));
    }

    private ExecutionHandle approve(Process process, ExecutionHandle waiting) {
        List<io.pipemesh.core.state.ApprovalRecord> pending =
                process.approvals().pendingFor(waiting.executionId());

        return process.runtime().resume(
                waiting.executionId(),
                new ResumeSignal.Approval(pending.get(0).approvalId(), true, "someone", null),
                Principal.SYSTEM);
    }

    @Test
    void anExecutionStartedBeforeTheDeployFinishesOnTheVersionItStartedOn() {
        Process before = boot(REVIEW_V1);
        ExecutionHandle waiting = startReview(before);
        assertEquals(ExecutionStatus.WAITING, waiting.status());

        Process after = boot(REVIEW_V1, REVIEW_V2);

        assertEquals(ExecutionStatus.COMPLETED, approve(after, waiting).status(),
                "approving on v2 cancels; COMPLETED means it resumed on v1");
    }

    @Test
    void theVersionSurvivesInTheRecordNotInTheProcess() {
        Process before = boot(REVIEW_V1);
        ExecutionHandle waiting = startReview(before);

        Process after = boot(REVIEW_V1, REVIEW_V2);

        assertEquals(WorkflowVersion.of("1.0"),
                after.runtime().snapshot(waiting.executionId()).orElseThrow().workflowVersion());
    }

    @Test
    void anExecutionStartedAfterTheDeployTakesTheNewVersion() {
        boot(REVIEW_V1);
        Process after = boot(REVIEW_V1, REVIEW_V2);

        ExecutionHandle waiting = startReview(after);

        assertEquals(WorkflowVersion.of("2.0"),
                after.runtime().snapshot(waiting.executionId()).orElseThrow().workflowVersion());
    }

    /**
     * A rollback removes the new version from the deployment, but executions
     * started on it are still in the database — and now have nowhere to resume.
     * That must be an explicit failure naming the version, not a wrong graph.
     */
    @Test
    void anExecutionOnARolledBackVersionSaysSoRatherThanRunningSomethingElse() {
        Process after = boot(REVIEW_V1, REVIEW_V2);
        ExecutionHandle onV2 = startReview(after);

        Process rolledBack = boot(REVIEW_V1);

        Exception refused = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> approve(rolledBack, onV2));

        org.junit.jupiter.api.Assertions.assertTrue(
                refused.getMessage().contains("review@2.0"), refused.getMessage());
    }
}
