package io.pipemesh.core.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.cost.ModelPrice;
import io.pipemesh.core.cost.ModelPrices;
import io.pipemesh.core.cost.SpendMeter;
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
import io.pipemesh.core.model.MessagingProvider;
import io.pipemesh.core.model.ModelId;
import io.pipemesh.core.prompt.InMemoryPromptRegistry;
import io.pipemesh.core.prompt.PromptId;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompilationException;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application decides which model suits the task; the runtime carries out
 * what it decided and judges nothing (§3, §39).
 */
class ModelRoutingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ROUTED = """
            {
              "id": "answer", "version": "1.0", "entry": "reply",
              "steps": [
                {"id": "reply", "type": "llm", "model": "$.input.route",
                 "models": ["cheap", "reasoning"], "prompt": "ask",
                 "output": "answer", "next": "done"},
                {"id": "done", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    private static final String STATIC = """
            {
              "id": "answer", "version": "1.0", "entry": "reply",
              "steps": [
                {"id": "reply", "type": "llm", "model": "cheap", "prompt": "ask",
                 "output": "answer", "next": "done"},
                {"id": "done", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    /** A run-time choice that never says what it is choosing from. */
    private static final String UNDECLARED = """
            {
              "id": "answer", "version": "1.0", "entry": "reply",
              "steps": [
                {"id": "reply", "type": "llm", "model": "$.input.route", "prompt": "ask",
                 "output": "answer", "next": "done"},
                {"id": "done", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    private final InMemoryStateStore stateStore = new InMemoryStateStore();

    /** Each provider answers with its own name, so the routing is observable. */
    private static MessagingProvider named(String name) {
        return new MessagingProvider() {
            @Override
            public String id() {
                return name;
            }

            @Override
            public CompletionResponse complete(CompletionRequest request) {
                return new CompletionResponse(
                        JsonNodeFactory.instance.textNode(name), name, 10, 10, 1);
            }
        };
    }

    private DefaultWorkflowRuntime runtime(String workflow, ModelPrices prices) {
        InMemoryModelRegistry models = new InMemoryModelRegistry();
        models.register(ModelId.of("cheap"), named("cheap"));
        models.register(ModelId.of("reasoning"), named("reasoning"));

        InMemoryPromptRegistry prompts = new InMemoryPromptRegistry();
        prompts.register(PromptId.of("ask"), "say something");

        StepExecutors executors = StepExecutors.of(
                new LlmStepExecutor(models, prompts), new TerminalStepExecutor());

        SpendMeter meter = new SpendMeter(prices);
        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors, meter));
        workflows.register(new WorkflowDefinitionReader().read(workflow));

        return new DefaultWorkflowRuntime(
                workflows, stateStore, new WorkflowExecutor(stateStore, executors));
    }

    private ExecutionHandle run(DefaultWorkflowRuntime runtime, String input) {
        try {
            return runtime.start(new ExecutionRequest(
                    WorkflowId.of("answer"),
                    new ExecutionInput((ObjectNode) JSON.readTree(input)),
                    OrganizationId.of("acme"), null));
        } catch (Exception malformed) {
            throw new IllegalStateException(malformed);
        }
    }

    private String answerOf(DefaultWorkflowRuntime runtime, ExecutionHandle handle) {
        return runtime.snapshot(handle.executionId()).orElseThrow()
                .variables().path("answer").asText();
    }

    @Test
    void aPlainAliasBehavesExactlyAsBefore() {
        DefaultWorkflowRuntime runtime = runtime(STATIC, ModelPrices.NONE);
        ExecutionHandle handle = run(runtime, "{}");

        assertEquals(ExecutionStatus.COMPLETED, handle.status());
        assertEquals("cheap", answerOf(runtime, handle));
    }

    @Test
    void theModelCanComeFromAVariable() {
        DefaultWorkflowRuntime runtime = runtime(ROUTED, ModelPrices.NONE);

        assertEquals("reasoning",
                answerOf(runtime, run(runtime, "{\"route\":\"reasoning\"}")));
        assertEquals("cheap",
                answerOf(runtime, run(runtime, "{\"route\":\"cheap\"}")));
    }

    @Test
    void choosingAtRunTimeWithoutSayingFromWhatIsRefusedAtLoadTime() {
        Exception refused = assertThrows(WorkflowCompilationException.class,
                () -> runtime(UNDECLARED, ModelPrices.NONE));

        assertTrue(refused.getMessage().contains("does not declare a 'models' list"),
                refused.getMessage());
    }

    @Test
    void aModelOutsideTheDeclaredSetIsRefused() {
        DefaultWorkflowRuntime runtime = runtime(ROUTED, ModelPrices.NONE);

        ExecutionHandle handle = run(runtime, "{\"route\":\"reasoning-pro\"}");

        assertEquals(ExecutionStatus.FAILED, handle.status());
        assertTrue(stateStore.historyOf(handle.executionId()).toString()
                .contains("llm.model_not_declared"));
    }

    @Test
    void anUnresolvableRouteFailsRatherThanPickingSomething() {
        DefaultWorkflowRuntime runtime = runtime(ROUTED, ModelPrices.NONE);

        ExecutionHandle handle = run(runtime, "{}");

        assertEquals(ExecutionStatus.FAILED, handle.status(),
                "falling back to a default nobody wrote down hides which model ran");
    }

    @Test
    void aMoneyBudgetChecksEveryModelTheStepCouldChoose() {
        String budgeted = ROUTED.replace("\"entry\": \"reply\",",
                "\"entry\": \"reply\", \"budget\": {\"maxCost\": \"1.00\"},");

        ModelPrices onlyOne = new ModelPrices(
                Map.of(ModelId.of("cheap"), ModelPrice.of("0.10", "0.40")));

        Exception refused = assertThrows(WorkflowCompilationException.class,
                () -> runtime(budgeted, onlyOne));

        assertTrue(refused.getMessage().contains("reasoning"), refused.getMessage());
        assertTrue(refused.getMessage().contains("no registered price"), refused.getMessage());
    }

    @Test
    void aMoneyBudgetIsHappyWhenEveryDeclaredModelIsPriced() {
        String budgeted = ROUTED.replace("\"entry\": \"reply\",",
                "\"entry\": \"reply\", \"budget\": {\"maxCost\": \"1.00\"},");

        ModelPrices both = new ModelPrices(Map.of(
                ModelId.of("cheap"), ModelPrice.of("0.10", "0.40"),
                ModelId.of("reasoning"), ModelPrice.of("1.25", "10.00")));

        assertEquals("cheap",
                answerOf(runtime(budgeted, both),
                        run(runtime(budgeted, both), "{\"route\":\"cheap\"}")));
    }
}
