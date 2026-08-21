package io.pipemesh.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.event.EventKey;
import io.pipemesh.core.event.EventPublisher;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.ExecutionHandle;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.execution.step.WaitStepExecutor;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A payment that arrives after a deploy still has to find the order that was
 * waiting for it (§9.7).
 */
class DurableWaitTest {

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

    /** Everything a single process would hold. Building a second one is the restart. */
    private record Process(DefaultWorkflowRuntime runtime, PostgresWaitStore waits) {
    }

    private Process boot() {
        DataSource dataSource = dataSource();
        PostgresStateStore stateStore = new PostgresStateStore(dataSource);
        PostgresWaitStore waits = new PostgresWaitStore(dataSource);

        StepExecutors executors = StepExecutors.of(
                new WaitStepExecutor(waits), new TerminalStepExecutor());

        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        workflows.register(new WorkflowDefinitionReader().read(SHIPPING));

        return new Process(
                new DefaultWorkflowRuntime(
                        workflows, stateStore, new WorkflowExecutor(stateStore, executors)),
                waits);
    }

    private ExecutionHandle awaitPayment(Process process, String order) {
        try {
            return process.runtime().start(new ExecutionRequest(
                    WorkflowId.of("shipping"),
                    new ExecutionInput((ObjectNode) JSON.readTree("{\"order\":\"" + order + "\"}")),
                    OrganizationId.of("acme"), null));
        } catch (Exception malformed) {
            throw new IllegalStateException(malformed);
        }
    }

    private EventKey key(String order) {
        return new EventKey(OrganizationId.of("acme"), "payment_completed", order);
    }

    @Test
    void anEventPublishedByAnotherProcessFindsTheWaitingExecution() {
        Process before = boot();
        ExecutionHandle waiting = awaitPayment(before, "A-4172");
        assertEquals(ExecutionStatus.WAITING, waiting.status());

        Process after = boot();
        List<ExecutionHandle> moved = new EventPublisher(after.waits(), after.runtime())
                .publish(key("A-4172"), JsonNodeFactory.instance.objectNode().put("amount", 90));

        assertEquals(1, moved.size());
        assertEquals(ExecutionStatus.COMPLETED, moved.get(0).status());
        assertEquals(90, after.runtime().snapshot(waiting.executionId()).orElseThrow()
                .variables().path("payment").path("amount").asInt());
    }

    @Test
    void aWaitOutlivesTheProcessThatFiledIt() {
        Process before = boot();
        awaitPayment(before, "A-4172");

        assertEquals(1, boot().waits().waitingFor(key("A-4172")).size());
    }

    @Test
    void anEventAboutAnotherOrderStillFindsNobody() {
        Process before = boot();
        ExecutionHandle waiting = awaitPayment(before, "A-4172");

        Process after = boot();
        assertTrue(new EventPublisher(after.waits(), after.runtime())
                .publish(key("B-9999"), JsonNodeFactory.instance.objectNode()).isEmpty());

        assertEquals(ExecutionStatus.WAITING,
                after.runtime().snapshot(waiting.executionId()).orElseThrow().status());
    }

    @Test
    void theSameEventAcrossTwoProcessesMovesItOnce() {
        Process before = boot();
        ExecutionHandle waiting = awaitPayment(before, "A-4172");
        var payload = JsonNodeFactory.instance.objectNode().put("amount", 90);

        var first = new EventPublisher(boot().waits(), boot().runtime()).publish(key("A-4172"), payload);
        var second = new EventPublisher(boot().waits(), boot().runtime()).publish(key("A-4172"), payload);

        assertEquals(1, first.size());
        assertTrue(second.isEmpty(), "the wait was already answered");
        assertEquals(ExecutionStatus.COMPLETED,
                boot().runtime().snapshot(waiting.executionId()).orElseThrow().status());
    }
}
