package io.pipemesh.postgres;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.pipemesh.core.capability.Principal;
import io.pipemesh.core.cost.ModelPrice;
import io.pipemesh.core.cost.ModelPrices;
import io.pipemesh.core.cost.Spend;
import io.pipemesh.core.cost.SpendMeter;
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
import io.pipemesh.core.execution.step.LlmStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.model.CompletionRequest;
import io.pipemesh.core.model.CompletionResponse;
import io.pipemesh.core.model.InMemoryModelRegistry;
import io.pipemesh.core.model.MessagingProvider;
import io.pipemesh.core.model.ModelId;
import io.pipemesh.core.observability.ExecutionObserver;
import io.pipemesh.core.prompt.InMemoryPromptRegistry;
import io.pipemesh.core.prompt.PromptId;
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
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A budget that forgets what was already spent is not a budget. Restarting has
 * to leave the total where it was (§39).
 */
class DurableSpendTest {

    /** One model call, then a person, then another model call. */
    private static final String REVIEW = """
            {
              "id": "review", "version": "1.0", "entry": "draft",
              "budget": { "maxModelCalls": 2 },
              "steps": [
                {"id": "draft", "type": "llm", "model": "reasoning", "prompt": "ask",
                 "output": "draft", "next": "check"},
                {"id": "check", "type": "human_approval", "message": "ok?",
                 "onApproved": "polish", "onRejected": "done"},
                {"id": "polish", "type": "llm", "model": "reasoning", "prompt": "ask",
                 "output": "polished", "next": "done"},
                {"id": "done", "type": "terminal", "status": "COMPLETED"}
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

    private Process boot() {
        DataSource dataSource = dataSource();
        PostgresStateStore stateStore = new PostgresStateStore(dataSource);
        PostgresApprovalStore approvals = new PostgresApprovalStore(dataSource);

        InMemoryModelRegistry models = new InMemoryModelRegistry();
        models.register(ModelId.of("reasoning"), new MessagingProvider() {
            @Override
            public String id() {
                return "scripted";
            }

            @Override
            public CompletionResponse complete(CompletionRequest request) {
                return new CompletionResponse(
                        JsonNodeFactory.instance.textNode("ok"), "scripted-1", 1000, 1000, 1);
            }
        });

        InMemoryPromptRegistry prompts = new InMemoryPromptRegistry();
        prompts.register(PromptId.of("ask"), "say something");

        StepExecutors executors = StepExecutors.of(
                new LlmStepExecutor(models, prompts),
                new ApprovalStepExecutor(approvals),
                new TerminalStepExecutor());

        SpendMeter meter = new SpendMeter(new ModelPrices(
                Map.of(ModelId.of("reasoning"), ModelPrice.of("1.25", "10.00"))));

        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors, meter));
        workflows.register(new WorkflowDefinitionReader().read(REVIEW));

        return new Process(
                new DefaultWorkflowRuntime(workflows, stateStore,
                        new WorkflowExecutor(stateStore, executors, Clock.systemUTC(),
                                WorkflowExecutor.DEFAULT_STEP_BUDGET, ExecutionObserver.NONE, meter)),
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
    void whatWasSpentBeforeTheRestartIsStillSpentAfterIt() {
        Process before = boot();
        ExecutionHandle waiting = startReview(before);
        assertEquals(ExecutionStatus.WAITING, waiting.status());

        Spend carried = boot().runtime()
                .snapshot(waiting.executionId()).orElseThrow().spend();

        assertEquals(1, carried.modelCalls());
        assertEquals(2000, carried.tokens());
        assertEquals(11_250L, carried.cost().micros());
    }

    @Test
    void aRestartedExecutionKeepsCountingFromWhereItWas() {
        Process before = boot();
        ExecutionHandle waiting = startReview(before);

        Process after = boot();
        ExecutionHandle finished = approve(after, waiting);

        Spend total = after.runtime().snapshot(waiting.executionId()).orElseThrow().spend();

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertEquals(2, total.modelCalls(), "one before the restart, one after");
        assertEquals(22_500L, total.cost().micros());
    }
}
