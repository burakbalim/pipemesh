package io.pipemesh.core.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pipemesh.core.execution.ExecutionContext;
import io.pipemesh.core.execution.StepExecutor;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.StepResult;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.ConditionStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.memory.InMemoryApprovalStore;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The door §23.1 exists to keep shut, tested from both sides: what the format
 * allows, and what it will not have.
 */
class WorkflowValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A step type that declares nothing, the way a third party's would. */
    private static final class UndeclaredStepExecutor implements StepExecutor {

        @Override
        public boolean supports(StepType type) {
            return StepType.of("bespoke").equals(type);
        }

        @Override
        public StepResult execute(Step step, ExecutionContext context) {
            return new StepResult.Failed("never", "not run in this test", false);
        }
    }

    private final WorkflowValidator validator = new WorkflowValidator(StepExecutors.of(
            new ConditionStepExecutor(),
            new ApprovalStepExecutor(new InMemoryApprovalStore()),
            new TerminalStepExecutor(),
            new UndeclaredStepExecutor()));

    private JsonNode workflow(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception malformed) {
            throw new IllegalArgumentException(malformed);
        }
    }

    private WorkflowShapeException refused(String json) {
        return assertThrows(WorkflowShapeException.class, () -> validator.validate(workflow(json)));
    }

    @Test
    void acceptsAWorkflowWrittenTheWayTheFormatDescribes() {
        validator.validate(workflow("""
                {
                  "id": "check", "version": "1.0", "entry": "validate",
                  "steps": [
                    {"id": "validate", "type": "condition", "expression": "$.input.ok == true",
                     "onTrue": "done", "onFalse": "done"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """));
    }

    @Test
    void refusesAStepCarryingCode() {
        WorkflowShapeException refused = refused("""
                {
                  "id": "sneaky", "version": "1.0", "entry": "validate",
                  "steps": [
                    {"id": "validate", "type": "condition", "expression": "$.a == 1",
                     "onTrue": "done", "onFalse": "done",
                     "code": "import os; os.system('rm -rf /')"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """);

        assertTrue(refused.getMessage().contains("code"), refused.getMessage());
    }

    @Test
    void saysWhichFieldItRefused() {
        WorkflowShapeException refused = refused("""
                {
                  "id": "typo", "version": "1.0", "entry": "done",
                  "steps": [{"id": "done", "type": "terminal", "statuss": "COMPLETED"}]
                }
                """);

        assertEquals("steps[0].statuss", refused.violations().get(0).path());
    }

    @Test
    void refusesAnUnknownFieldOnTheWorkflowItself() {
        WorkflowShapeException refused = refused("""
                {
                  "id": "check", "version": "1.0", "entry": "done", "script": "curl evil.sh",
                  "steps": [{"id": "done", "type": "terminal", "status": "COMPLETED"}]
                }
                """);

        assertEquals("$.script", refused.violations().get(0).path());
    }

    @Test
    void allowsTheFieldsEveryStepMayCarry() {
        validator.validate(workflow("""
                {
                  "id": "check", "version": "1.0", "entry": "validate",
                  "steps": [
                    {"id": "validate", "type": "condition", "expression": "$.a == 1",
                     "onTrue": "done", "onFalse": "done",
                     "description": "is it there",
                     "timeout": "5s",
                     "retry": {"maxAttempts": 2, "backoff": "exponential", "initialDelay": "1s"},
                     "onFailure": {"strategy": "goto", "goto": "done"}},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """));
    }

    @Test
    void refusesAnUnknownRetrySetting() {
        WorkflowShapeException refused = refused("""
                {
                  "id": "check", "version": "1.0", "entry": "done",
                  "steps": [{"id": "done", "type": "terminal", "status": "COMPLETED",
                             "retry": {"maxAttempts": 2, "untilItWorks": true}}]
                }
                """);

        assertTrue(refused.getMessage().contains("untilItWorks"));
    }

    @Test
    void refusesAStepMissingSomethingItsTypeRequires() {
        WorkflowShapeException refused = refused("""
                {
                  "id": "check", "version": "1.0", "entry": "validate",
                  "steps": [
                    {"id": "validate", "type": "condition", "onTrue": "done", "onFalse": "done"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """);

        assertTrue(refused.getMessage().contains("expression"));
    }

    @Test
    void refusesAStatusThatIsNotOne() {
        assertTrue(refused("""
                {
                  "id": "check", "version": "1.0", "entry": "done",
                  "steps": [{"id": "done", "type": "terminal", "status": "PROBABLY"}]
                }
                """).getMessage().contains("status"));
    }

    @Test
    void leavesAStepTypeThatDeclaredNothingAlone() {
        validator.validate(workflow("""
                {
                  "id": "check", "version": "1.0", "entry": "custom",
                  "steps": [{"id": "custom", "type": "bespoke", "whateverItWants": 42}]
                }
                """));
    }

    @Test
    void saysNothingAboutAStepTypeNobodyClaims() {
        // The compiler reports that, in words about the type rather than about JSON.
        validator.validate(workflow("""
                {
                  "id": "check", "version": "1.0", "entry": "guess",
                  "steps": [{"id": "guess", "type": "telepathy", "code": "anything"}]
                }
                """));
    }

    @Test
    void reportsEverythingWrongAtOnce() {
        WorkflowShapeException refused = refused("""
                {
                  "id": "check", "version": "1.0", "entry": "validate", "script": "x",
                  "steps": [
                    {"id": "validate", "type": "condition", "onTrue": "done", "onFalse": "done",
                     "code": "y"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """);

        assertEquals(3, refused.violations().size(), refused.getMessage());
    }
}
