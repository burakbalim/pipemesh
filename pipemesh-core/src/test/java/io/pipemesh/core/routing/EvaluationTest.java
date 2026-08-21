package io.pipemesh.core.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.pipemesh.core.capability.CapabilityCall;
import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityId;
import io.pipemesh.core.capability.CapabilityKind;
import io.pipemesh.core.capability.CapabilityProvider;
import io.pipemesh.core.capability.CapabilityResult;
import io.pipemesh.core.capability.InMemoryCapabilityRegistry;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.ExecutionHandle;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.CapabilityStepExecutor;
import io.pipemesh.core.execution.step.ConditionStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.memory.InMemoryApprovalStore;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * §39 lists evaluation as a future extension. This is the argument that it needs
 * no runtime feature: what "good" means is the application's knowledge (§3), so
 * scoring is a capability and the threshold is a condition.
 *
 * <p>The test exists to keep that claim honest. If it ever stops passing, the
 * claim in DESIGN.md §39.2 has stopped being true.
 */
class EvaluationTest {

    private static final String ANSWER_WITH_REVIEW = """
            {
              "id": "graded", "version": "1.0", "entry": "score",
              "steps": [
                {"id": "score", "type": "capability", "capability": "grade_answer",
                 "input": "$.input", "output": "quality", "next": "gate"},
                {"id": "gate", "type": "condition", "expression": "$.quality.score < 0.6",
                 "onTrue": "ask_human", "onFalse": "deliver"},
                {"id": "ask_human", "type": "human_approval", "message": "Answer looks weak. Send it?",
                 "onApproved": "deliver", "onRejected": "dropped"},
                {"id": "deliver", "type": "terminal", "status": "COMPLETED"},
                {"id": "dropped", "type": "terminal", "status": "CANCELLED"}
              ]
            }
            """;

    private final InMemoryStateStore stateStore = new InMemoryStateStore();

    /** The application's judgment, reached the same way any other capability is. */
    private record Grader(double score) implements CapabilityProvider {

        @Override
        public String type() {
            return "in-process";
        }

        @Override
        public CapabilityResult invoke(
                CapabilityDescriptor capability, JsonNode input, CapabilityCall call) {

            return new CapabilityResult.Success(
                    JsonNodeFactory.instance.objectNode().put("score", score));
        }
    }

    private DefaultWorkflowRuntime runtime(double score) {
        InMemoryCapabilityRegistry capabilities = new InMemoryCapabilityRegistry().register(
                new CapabilityDescriptor(
                        CapabilityId.of("grade_answer"), "How good is this answer",
                        CapabilityKind.APPLICATION, "quality-team", "1.0",
                        List.of(), null, null,
                        JsonNodeFactory.instance.objectNode().put("type", "in-process")));

        StepExecutors executors = StepExecutors.of(
                new CapabilityStepExecutor(capabilities, List.of(new Grader(score))),
                new ConditionStepExecutor(),
                new ApprovalStepExecutor(new InMemoryApprovalStore()),
                new TerminalStepExecutor());

        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        workflows.register(new WorkflowDefinitionReader().read(ANSWER_WITH_REVIEW));

        return new DefaultWorkflowRuntime(
                workflows, stateStore, new WorkflowExecutor(stateStore, executors));
    }

    private ExecutionHandle run(double score) {
        return runtime(score).start(new ExecutionRequest(
                WorkflowId.of("graded"), ExecutionInput.empty(),
                OrganizationId.of("acme"), null));
    }

    @Test
    void aGoodEnoughAnswerGoesStraightOut() {
        assertEquals(ExecutionStatus.COMPLETED, run(0.9).status());
    }

    @Test
    void aWeakAnswerStopsForAPerson() {
        ExecutionHandle handle = run(0.3);

        assertEquals(ExecutionStatus.WAITING, handle.status());
        assertEquals("ask_human", handle.currentStep().value());
    }

    @Test
    void theScoreIsWrittenDownLikeAnyOtherStepOutput() {
        ExecutionHandle handle = run(0.3);

        assertEquals(0.3, runtime(0.3).snapshot(handle.executionId()).orElseThrow()
                .variables().path("quality").path("score").asDouble());
    }
}
