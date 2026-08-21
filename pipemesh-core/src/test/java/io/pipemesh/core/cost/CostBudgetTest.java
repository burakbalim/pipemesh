package io.pipemesh.core.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.ExecutionHandle;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.LlmStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.model.CompletionRequest;
import io.pipemesh.core.model.CompletionResponse;
import io.pipemesh.core.model.InMemoryModelRegistry;
import io.pipemesh.core.model.ModelId;
import io.pipemesh.core.model.MessagingProvider;
import io.pipemesh.core.observability.ExecutionObserver;
import io.pipemesh.core.prompt.InMemoryPromptRegistry;
import io.pipemesh.core.prompt.PromptId;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompilationException;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The missing member of a list this codebase keeps writing: a step budget, an
 * agent turn limit, a worker deadline, a wait timeout — and now money (§39).
 */
class CostBudgetTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Three model calls in a row, so a budget has something to stop. */
    private static final String RESEARCH = """
            {
              "id": "research", "version": "1.0", "entry": "one",
              %s
              "steps": [
                {"id": "one", "type": "llm", "model": "reasoning", "prompt": "ask",
                 "output": "a", "next": "two"},
                {"id": "two", "type": "llm", "model": "reasoning", "prompt": "ask",
                 "output": "b", "next": "three"},
                {"id": "three", "type": "llm", "model": "reasoning", "prompt": "ask",
                 "output": "c", "next": "done"},
                {"id": "done", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    /** 1000 in + 1000 out per call: $1.25 + $10.00 per million → 11250 micros. */
    private static final ModelPrice REASONING_PRICE = ModelPrice.of("1.25", "10.00");

    private final InMemoryStateStore stateStore = new InMemoryStateStore();

    private static MessagingProvider scripted() {
        return new MessagingProvider() {
            @Override
            public String id() {
                return "scripted";
            }

            @Override
            public CompletionResponse complete(CompletionRequest request) {
                return new CompletionResponse(
                        JSON.getNodeFactory().textNode("ok"), "scripted-1", 1000, 1000, 1);
            }
        };
    }

    private DefaultWorkflowRuntime runtime(String budget, ModelPrices prices) {
        InMemoryModelRegistry models = new InMemoryModelRegistry();
        models.register(ModelId.of("reasoning"), scripted());

        InMemoryPromptRegistry prompts = new InMemoryPromptRegistry();
        prompts.register(PromptId.of("ask"), "say something");

        StepExecutors executors = StepExecutors.of(
                new LlmStepExecutor(models, prompts), new TerminalStepExecutor());

        SpendMeter meter = new SpendMeter(prices);
        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors, meter));
        workflows.register(new WorkflowDefinitionReader().read(RESEARCH.formatted(budget)));

        return new DefaultWorkflowRuntime(workflows, stateStore,
                new WorkflowExecutor(stateStore, executors, Clock.systemUTC(),
                        WorkflowExecutor.DEFAULT_STEP_BUDGET, ExecutionObserver.NONE, meter));
    }

    private ModelPrices priced() {
        return new ModelPrices(Map.of(ModelId.of("reasoning"), REASONING_PRICE));
    }

    private ExecutionHandle run(String budget, ModelPrices prices) {
        return runtime(budget, prices).start(new ExecutionRequest(
                WorkflowId.of("research"), ExecutionInput.empty(),
                OrganizationId.of("acme"), null));
    }

    private Spend spendOf(DefaultWorkflowRuntime runtime, ExecutionHandle handle) {
        return runtime.snapshot(handle.executionId()).orElseThrow().spend();
    }

    @Test
    void moneyIsCountedInIntegers() {
        assertEquals(2_500_000L, Money.parse("2.50").micros());
        assertEquals("2.50", Money.parse("2.50").toString());
        assertEquals(11_250L, REASONING_PRICE.costOf(1000, 1000).micros());
    }

    @Test
    void aRunRecordsWhatItSpent() {
        DefaultWorkflowRuntime runtime = runtime("", priced());
        ExecutionHandle handle = runtime.start(new ExecutionRequest(
                WorkflowId.of("research"), ExecutionInput.empty(),
                OrganizationId.of("acme"), null));

        Spend spend = spendOf(runtime, handle);

        assertEquals(ExecutionStatus.COMPLETED, handle.status());
        assertEquals(3, spend.modelCalls());
        assertEquals(6000, spend.tokens());
        assertEquals(33_750L, spend.cost().micros(), "three calls at 11250");
    }

    @Test
    void aWorkflowWithNoBudgetIsUnchanged() {
        assertEquals(ExecutionStatus.COMPLETED, run("", ModelPrices.NONE).status());
    }

    @Test
    void anExecutionThatOverspendsMoneyStops() {
        DefaultWorkflowRuntime runtime = runtime("\"budget\": {\"maxCost\": \"0.02\"},", priced());
        ExecutionHandle handle = runtime.start(new ExecutionRequest(
                WorkflowId.of("research"), ExecutionInput.empty(),
                OrganizationId.of("acme"), null));

        assertEquals(ExecutionStatus.FAILED, handle.status());
        assertEquals(2, spendOf(runtime, handle).modelCalls(),
                "stopped before the third, having overrun on the second");
    }

    @Test
    void tooManyModelCallsStopsIt() {
        assertEquals(ExecutionStatus.FAILED,
                run("\"budget\": {\"maxModelCalls\": 2},", priced()).status());
    }

    @Test
    void tooManyTokensStopsIt() {
        assertEquals(ExecutionStatus.FAILED,
                run("\"budget\": {\"maxTokens\": 3000},", priced()).status());
    }

    @Test
    void landingExactlyOnTheLimitIsNotOverrunningIt() {
        assertEquals(ExecutionStatus.COMPLETED,
                run("\"budget\": {\"maxModelCalls\": 3},", priced()).status(),
                "three calls against a budget of three finished, it did not overrun");

        assertEquals(ExecutionStatus.COMPLETED,
                run("\"budget\": {\"maxTokens\": 6000},", priced()).status());
    }

    @Test
    void theFailureSaysWhichBudgetAndWhy() {
        DefaultWorkflowRuntime runtime = runtime("\"budget\": {\"maxModelCalls\": 1},", priced());
        ExecutionHandle handle = runtime.start(new ExecutionRequest(
                WorkflowId.of("research"), ExecutionInput.empty(),
                OrganizationId.of("acme"), null));

        String history = stateStore.historyOf(handle.executionId()).toString();

        assertTrue(history.contains("execution.budget_exhausted"), history);
        assertTrue(history.contains("model calls exceed budget 1"), history);
    }

    @Test
    void aMoneyBudgetIsRefusedAModelNobodyPriced() {
        Exception refused = assertThrows(WorkflowCompilationException.class,
                () -> runtime("\"budget\": {\"maxCost\": \"5.00\"},", ModelPrices.NONE));

        assertTrue(refused.getMessage().contains("no registered price"), refused.getMessage());
    }

    @Test
    void anUnpricedModelWithoutAMoneyBudgetStillRuns() {
        DefaultWorkflowRuntime runtime =
                runtime("\"budget\": {\"maxModelCalls\": 10},", ModelPrices.NONE);
        ExecutionHandle handle = runtime.start(new ExecutionRequest(
                WorkflowId.of("research"), ExecutionInput.empty(),
                OrganizationId.of("acme"), null));

        Spend spend = spendOf(runtime, handle);

        assertEquals(ExecutionStatus.COMPLETED, handle.status());
        assertEquals(3, spend.unpricedCalls(), "counted, but not priced");
        assertTrue(spend.cost().isZero(), "an unpriced call adds nothing to a money total");
    }

    @Test
    void aPriceInOnlyOneDirectionIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPrice(BigDecimal.valueOf(-1), BigDecimal.ONE));
    }
}
