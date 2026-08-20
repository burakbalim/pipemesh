package io.pipemesh.postgres;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityId;
import io.pipemesh.core.capability.CapabilityKind;
import io.pipemesh.core.capability.CapabilityProvider;
import io.pipemesh.core.capability.CapabilityResult;
import io.pipemesh.core.capability.InMemoryCapabilityRegistry;
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.execution.RecoverySweeper;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.CapabilityStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.observability.ExecutionObserver;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.StepId;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gap this closes, against a real database: a process dies mid-step and the
 * execution sits in RUNNING with nobody to pick it up.
 *
 * <p>The orphan is made the way a crash makes one — a row written and then
 * abandoned, its {@code updated_at} pushed into the past — and recovered by a
 * runtime built fresh, exactly as a different machine would.
 */
class OrphanRecoveryTest {

    private static final String WORKFLOW = """
            {
              "id": "lookup_flow", "version": "1.0", "entry": "call",
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
                                       com.fasterxml.jackson.databind.JsonNode input) {
            return new CapabilityResult.Success(JsonNodeFactory.instance.objectNode().put("found", 1));
        }
    }

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

    /** Writes an execution and backdates it, which is what a crash leaves behind. */
    private ExecutionId abandonedExecution(boolean idempotent, Duration ago) throws Exception {
        PostgresStateStore store = new PostgresStateStore(dataSource());
        ExecutionId id = ExecutionId.generate();

        store.create(new ExecutionRecord(
                id, OrganizationId.of("acme"), WorkflowId.of("lookup_flow"),
                WorkflowVersion.of("1.0"), ExecutionStatus.RUNNING, StepId.of("call"),
                JsonNodeFactory.instance.objectNode(),
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", 0L, 0L, 0L));

        try (var connection = dataSource().getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE workflow_execution SET updated_at = ? WHERE execution_id = ?")) {
            statement.setTimestamp(1, new Timestamp(System.currentTimeMillis() - ago.toMillis()));
            statement.setString(2, id.value());
            statement.executeUpdate();
        }
        return id;
    }

    /** A fresh runtime, sharing nothing with whatever wrote the orphan. */
    private RecoverySweeper sweeperFor(boolean idempotent) {
        DataSource dataSource = dataSource();
        PostgresStateStore stateStore = new PostgresStateStore(dataSource);

        var execution = JsonNodeFactory.instance.objectNode().put("type", "grpc");
        if (!idempotent) {
            execution.put("idempotent", false);
        }
        InMemoryCapabilityRegistry capabilities = new InMemoryCapabilityRegistry().register(
                new CapabilityDescriptor(CapabilityId.of("lookup"), "", CapabilityKind.EXTERNAL,
                        "team", "1.0", List.of(), null, null, execution));

        StepExecutors executors = StepExecutors.of(
                new CapabilityStepExecutor(capabilities, List.of(new AlwaysSucceeds("grpc"))),
                new TerminalStepExecutor());

        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        workflows.register(new WorkflowDefinitionReader().read(WORKFLOW));

        return new RecoverySweeper(
                workflows, stateStore, new WorkflowExecutor(stateStore, executors),
                executors, Clock.systemUTC(), Duration.ofMinutes(5), 50, ExecutionObserver.NONE);
    }

    private ExecutionRecord read(ExecutionId id) {
        return new PostgresStateStore(dataSource()).find(id).orElseThrow();
    }

    @Test
    void finishesAnExecutionItsOwnProcessNeverCompleted() throws Exception {
        ExecutionId orphan = abandonedExecution(true, Duration.ofMinutes(30));

        assertEquals(1, sweeperFor(true).sweep());
        assertEquals(ExecutionStatus.COMPLETED, read(orphan).status());
    }

    @Test
    void leavesAnExecutionWhoseProcessMayStillBeWorking() throws Exception {
        ExecutionId recent = abandonedExecution(true, Duration.ofSeconds(20));

        assertEquals(0, sweeperFor(true).sweep());
        assertEquals(ExecutionStatus.RUNNING, read(recent).status());
    }

    @Test
    void stopsRatherThanRepeatingSomethingThatMayHaveHappened() throws Exception {
        ExecutionId orphan = abandonedExecution(false, Duration.ofMinutes(30));

        sweeperFor(false).sweep();

        assertEquals(ExecutionStatus.FAILED, read(orphan).status());

        var history = new PostgresStateStore(dataSource()).historyOf(orphan);
        assertEquals("execution.unrecoverable",
                history.get(history.size() - 1).output().path("code").asText());
    }

    @Test
    void staysInTheTraceItWasAbandonedIn() throws Exception {
        ExecutionId orphan = abandonedExecution(true, Duration.ofMinutes(30));
        String traceBefore = read(orphan).traceContext();

        sweeperFor(true).sweep();

        assertEquals(traceBefore, read(orphan).traceContext());
        assertTrue(traceBefore.startsWith("00-"));
    }

    @Test
    void twoSweepersRacingOverTheSameOrphanRecoverItOnce() throws Exception {
        ExecutionId orphan = abandonedExecution(true, Duration.ofMinutes(30));

        int first = sweeperFor(true).sweep();
        int second = sweeperFor(true).sweep();

        assertEquals(1, first);
        assertEquals(0, second, "once recovered it is no longer stale");
        assertEquals(ExecutionStatus.COMPLETED, read(orphan).status());

        long callsRecorded = new PostgresStateStore(dataSource()).historyOf(orphan).stream()
                .filter(entry -> entry.stepId().equals(StepId.of("call")))
                .count();
        assertEquals(1, callsRecorded, "the capability step ran once, not twice");
    }
}
