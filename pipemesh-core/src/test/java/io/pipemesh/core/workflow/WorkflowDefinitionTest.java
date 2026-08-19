package io.pipemesh.core.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowDefinitionTest {

    private static Step step(String id) {
        return new Step(StepId.of(id), StepType.TERMINAL, null);
    }

    private static WorkflowDefinition definition(List<Step> steps) {
        return new WorkflowDefinition(
                WorkflowId.of("venue_booking"), WorkflowVersion.of("1.0"), StepId.of("a"), steps);
    }

    @Test
    void findsAStepById() {
        WorkflowDefinition definition = definition(List.of(step("a"), step("b")));

        assertTrue(definition.step(StepId.of("b")).isPresent());
        assertTrue(definition.step(StepId.of("missing")).isEmpty());
    }

    @Test
    void rejectsDuplicateStepIds() {
        WorkflowDefinition definition = definition(List.of(step("a"), step("a")));

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, definition::stepsById);

        assertTrue(failure.getMessage().contains("duplicate step id"));
    }

    @Test
    void rejectsAWorkflowWithoutSteps() {
        assertThrows(IllegalArgumentException.class, () -> definition(List.of()));
    }

    @Test
    void keepsStepOrder() {
        WorkflowDefinition definition = definition(List.of(step("a"), step("b")));

        assertEquals(List.of(StepId.of("a"), StepId.of("b")),
                definition.stepsById().keySet().stream().toList());
    }
}
