package io.pipemesh.postgres;

import io.pipemesh.core.dispatch.ExecutionDispatcher;
import io.pipemesh.core.dispatch.ExecutionLease;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.ExecutionHandle;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.execution.StartMode;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two processes and one database — the only arrangement that proves anything
 * about distribution (§28). In-memory leases can show arbitration; only this can
 * show a claim outliving the process that took it.
 */
class DistributedDispatchTest {

    private static final String NOTIFY = """
            {
              "id": "notify", "version": "1.0", "entry": "done",
              "steps": [{"id": "done", "type": "terminal", "status": "COMPLETED"}]
            }
            """;

    private static final String REVIEW = """
            {
              "id": "review", "version": "1.0", "entry": "decide",
              "steps": [
                {"id": "decide", "type": "human_approval", "message": "ok?",
                 "onApproved": "done", "onRejected": "done"},
                {"id": "done", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    private static PostgreSQLContainer<?> postgres;

    private final MovableClock clock = new MovableClock(Instant.parse("2026-08-21T09:00:00Z"));
    private final List<ExecutionDispatcher> running = new ArrayList<>();

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
    void stop() {
        running.forEach(ExecutionDispatcher::close);
        running.clear();
        TestTables.empty(dataSource());
    }

    private DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }

    /** One process: its own connections, its own dispatcher, the same database. */
    private record Instance(DefaultWorkflowRuntime runtime,
                            ExecutionDispatcher dispatcher,
                            PostgresExecutionLeases leases) {
    }

    private Instance boot(String name) {
        DataSource dataSource = dataSource();
        PostgresStateStore stateStore = new PostgresStateStore(dataSource);
        PostgresExecutionLeases leases = new PostgresExecutionLeases(dataSource, clock);

        StepExecutors executors = StepExecutors.of(
                new ApprovalStepExecutor(new PostgresApprovalStore(dataSource)),
                new TerminalStepExecutor());

        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        workflows.register(new WorkflowDefinitionReader().read(NOTIFY));
        workflows.register(new WorkflowDefinitionReader().read(REVIEW));

        WorkflowExecutor executor = new WorkflowExecutor(stateStore, executors);
        ExecutionDispatcher dispatcher = new ExecutionDispatcher(
                workflows, stateStore, executor, leases, name, Duration.ofMinutes(5), 10);
        running.add(dispatcher);

        return new Instance(
                new DefaultWorkflowRuntime(
                        workflows, stateStore, executor, null, StartMode.DISPATCHED),
                dispatcher,
                leases);
    }

    private ExecutionHandle enqueue(Instance instance, String workflow) {
        return instance.runtime().start(new ExecutionRequest(
                WorkflowId.of(workflow), ExecutionInput.empty(),
                OrganizationId.of("acme"), null));
    }

    private void settle() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(300);
    }

    @Test
    void workEnqueuedOnOneInstanceIsFinishedByAnother() throws InterruptedException {
        Instance producer = boot("pod-a");
        Instance consumer = boot("pod-b");

        ExecutionHandle handle = enqueue(producer, "notify");
        assertEquals(ExecutionStatus.CREATED, handle.status());

        assertEquals(1, consumer.dispatcher().dispatchOnce());
        settle();

        assertEquals(ExecutionStatus.COMPLETED,
                producer.runtime().snapshot(handle.executionId()).orElseThrow().status());
    }

    @Test
    void twoInstancesLookingAtOnceDoNotBothTakeIt() {
        Instance first = boot("pod-a");
        Instance second = boot("pod-b");
        enqueue(first, "review");

        int taken = first.dispatcher().dispatchOnce() + second.dispatcher().dispatchOnce();

        assertEquals(1, taken, "SKIP LOCKED means one of them steps over it");
    }

    /**
     * Lease lifetime is asked of the leases directly. {@code dispatchOnce} also
     * drives, and an execution driven to WAITING stops being claimable for a
     * reason that has nothing to do with its lease — asking through the
     * dispatcher would make this a race rather than a test.
     */
    @Test
    void aClaimTakenByAProcessThatDiedIsPickedUpWhenItExpires() {
        Instance died = boot("pod-a");
        enqueue(died, "review");
        assertEquals(1, died.leases().claim("pod-a", Duration.ofMinutes(5), 10).size());

        Instance survivor = boot("pod-b");
        assertEquals(0, survivor.leases().claim("pod-b", Duration.ofMinutes(5), 10).size(),
                "still owned");

        clock.advance(Duration.ofMinutes(6));

        assertEquals(1, survivor.leases().claim("pod-b", Duration.ofMinutes(5), 10).size(),
                "nobody renewed it");
    }

    @Test
    void aRenewedClaimIsNotTakenOver() {
        Instance holder = boot("pod-a");
        enqueue(holder, "review");
        List<ExecutionLease> held = holder.leases().claim("pod-a", Duration.ofMinutes(5), 10);

        clock.advance(Duration.ofMinutes(4));
        assertEquals(1, holder.leases().renew(held.get(0), Duration.ofMinutes(5)).stream().count());
        clock.advance(Duration.ofMinutes(4));

        assertEquals(0, boot("pod-b").leases().claim("pod-b", Duration.ofMinutes(5), 10).size());
    }

    @Test
    void anOwnerThatCameBackWithTheSameNameCannotRenewItsOldLease() {
        Instance first = boot("pod-a");
        enqueue(first, "review");
        List<ExecutionLease> before = first.leases().claim("pod-a", Duration.ofMinutes(5), 10);
        assertEquals(1, before.size());

        clock.advance(Duration.ofMinutes(6));
        Instance restarted = boot("pod-a");
        assertEquals(1, restarted.leases().claim("pod-a", Duration.ofMinutes(5), 10).size());

        assertTrue(first.leases().renew(before.get(0), Duration.ofMinutes(5)).isEmpty(),
                "the previous life's token is not the current claim");
    }

    @Test
    void aFinishedExecutionIsNobodysWork() throws InterruptedException {
        Instance instance = boot("pod-a");
        enqueue(instance, "notify");
        instance.dispatcher().dispatchOnce();
        settle();

        assertEquals(0, boot("pod-b").dispatcher().dispatchOnce());
    }

    @Test
    void anExecutionWaitingForAPersonIsNotHandedToADriver() throws InterruptedException {
        Instance instance = boot("pod-a");
        ExecutionHandle handle = enqueue(instance, "review");
        instance.dispatcher().dispatchOnce();
        settle();

        assertEquals(ExecutionStatus.WAITING,
                instance.runtime().snapshot(handle.executionId()).orElseThrow().status());

        clock.advance(Duration.ofMinutes(6));

        assertEquals(0, boot("pod-b").leases().claim("pod-b", Duration.ofMinutes(5), 10).size(),
                "waiting is not stuck");
    }

    /** The same predicate the claim uses, asked instead of acted on (§22.1). */
    @Test
    void theBacklogCountsWhatNobodyIsDriving() {
        Instance instance = boot("pod-a");
        enqueue(instance, "review");
        enqueue(instance, "review");

        assertEquals(2, instance.leases().backlog().size());

        instance.leases().claim("pod-a", Duration.ofMinutes(5), 1);

        assertEquals(1, instance.leases().backlog().size(), "one is being driven now");
    }

    @Test
    void theBacklogIsEmptyWhenNothingIsQueued() {
        assertEquals(0, boot("pod-a").leases().backlog().size());
        assertEquals(0, boot("pod-a").leases().backlog().oldestWaitingMillis());
    }

    @Test
    void oneRoundNeverTakesMoreThanItsBatch() {
        Instance instance = boot("pod-a");
        for (int queued = 0; queued < 5; queued++) {
            enqueue(instance, "review");
        }

        assertEquals(2, instance.leases().claim("pod-c", Duration.ofMinutes(5), 2).size());
    }
}
