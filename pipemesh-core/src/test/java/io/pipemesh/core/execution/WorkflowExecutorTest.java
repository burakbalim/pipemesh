package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.execution.step.ConditionStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.state.StepRecord;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.ExecutionGraph;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TIER_CHECK = """
            {
              "id": "tier_check", "version": "1.0", "entry": "validate",
              "steps": [
                {"id": "validate", "type": "condition",
                 "expression": "$.input.tier == 'gold'",
                 "onTrue": "approved", "onFalse": "rejected"},
                {"id": "approved", "type": "terminal", "status": "COMPLETED"},
                {"id": "rejected", "type": "terminal", "status": "CANCELLED"}
              ]
            }
            """;

    private final InMemoryStateStore stateStore = new InMemoryStateStore();

    private final StepExecutors executors =
            StepExecutors.of(new ConditionStepExecutor(), new TerminalStepExecutor());

    private final InMemoryWorkflowRegistry registry =
            new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));

    private final WorkflowExecutor executor = new WorkflowExecutor(stateStore, executors);

    private ExecutionGraph register(String json) {
        return registry.register(new WorkflowDefinitionReader().read(json));
    }

    private ExecutionInput input(String json) {
        try {
            return new ExecutionInput((ObjectNode) JSON.readTree(json));
        } catch (Exception malformed) {
            throw new IllegalArgumentException(malformed);
        }
    }

    private ExecutionRecord run(String workflowJson, String inputJson) {
        return executor.start(register(workflowJson), ExecutionId.generate(), input(inputJson));
    }

    @Test
    void runsAWorkflowDefinedEntirelyInJson() {
        ExecutionRecord finished = run(TIER_CHECK, "{\"tier\":\"gold\"}");

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertEquals(StepId.of("approved"), finished.currentStep());
    }

    @Test
    void takesTheFalseBranchToADifferentTerminalStatus() {
        ExecutionRecord finished = run(TIER_CHECK, "{\"tier\":\"silver\"}");

        assertEquals(ExecutionStatus.CANCELLED, finished.status());
        assertEquals(StepId.of("rejected"), finished.currentStep());
    }

    @Test
    void runsASecondWorkflowWithoutAnyCodeChange() {
        ExecutionRecord finished = run("""
                {
                  "id": "budget_check", "version": "2.1", "entry": "expensive",
                  "steps": [
                    {"id": "expensive", "type": "condition", "expression": "$.input.price > 100",
                     "onTrue": "flagged", "onFalse": "cleared"},
                    {"id": "flagged", "type": "terminal", "status": "CANCELLED"},
                    {"id": "cleared", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, "{\"price\":250}");

        assertEquals(ExecutionStatus.CANCELLED, finished.status());
    }

    @Test
    void recordsEveryStepItRan() {
        ExecutionRecord finished = run(TIER_CHECK, "{\"tier\":\"gold\"}");

        List<StepRecord> history = stateStore.historyOf(finished.executionId());

        assertEquals(2, history.size());
        assertEquals(StepId.of("validate"), history.get(0).stepId());
        assertEquals(StepId.of("approved"), history.get(1).stepId());
        assertTrue(history.stream()
                .allMatch(step -> step.outcome() == StepRecord.StepOutcome.SUCCESS));
    }

    @Test
    void keepsTheInputAvailableAsAVariable() {
        ExecutionRecord finished = run(TIER_CHECK, "{\"tier\":\"gold\"}");

        assertEquals("gold", finished.variables().path("input").path("tier").asText());
    }

    @Test
    void versionsTheExecutionOnEveryWrite() {
        ExecutionRecord finished = run(TIER_CHECK, "{\"tier\":\"gold\"}");

        assertEquals(3, finished.version(), "one create plus two steps");
    }

    @Test
    void failsAConditionWhoseExpressionCannotBeEvaluated() {
        ExecutionRecord finished = run("""
                {
                  "id": "broken", "version": "1.0", "entry": "validate",
                  "steps": [
                    {"id": "validate", "type": "condition", "expression": "$.input.tier > 5",
                     "onTrue": "done", "onFalse": "done"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """, "{\"tier\":\"gold\"}");

        assertEquals(ExecutionStatus.FAILED, finished.status());
        assertEquals("condition.invalid_expression",
                stateStore.historyOf(finished.executionId()).get(0).output().path("code").asText());
    }

    @Test
    void stopsAnEndlessLoopInsteadOfSpinningForever() {
        WorkflowExecutor bounded = new WorkflowExecutor(
                stateStore, executors, java.time.Clock.systemUTC(), 5);

        ExecutionGraph graph = register("""
                {
                  "id": "endless", "version": "1.0", "entry": "again",
                  "steps": [
                    {"id": "again", "type": "condition", "expression": "$.input.go == true",
                     "onTrue": "again", "onFalse": "done"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """);

        ExecutionRecord finished =
                bounded.start(graph, ExecutionId.generate(), input("{\"go\":true}"));

        assertEquals(ExecutionStatus.FAILED, finished.status());

        List<StepRecord> history = stateStore.historyOf(finished.executionId());
        assertEquals("execution.step_budget_exhausted",
                history.get(history.size() - 1).output().path("code").asText());
    }
}
