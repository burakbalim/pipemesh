package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.WorkflowId;
import io.pipemesh.core.workflow.WorkflowVersion;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionContextTest {

    private static ExecutionContext context(ObjectNode variables) {
        return new ExecutionContext(
                ExecutionId.of("exec-1"),
                OrganizationId.DEFAULT,
                WorkflowId.of("venue_booking"),
                WorkflowVersion.of("1.0"),
                StepId.of("extract"),
                variables);
    }

    @Test
    void mergingVariablesLeavesTheOriginalUntouched() {
        ExecutionContext original = context(JsonNodeFactory.instance.objectNode().put("a", 1));

        ExecutionContext merged =
                original.with(Map.of("b", JsonNodeFactory.instance.numberNode(2)));

        assertTrue(original.variable("b").isMissingNode());
        assertEquals(1, merged.variable("a").asInt());
        assertEquals(2, merged.variable("b").asInt());
    }

    @Test
    void mergingOverwritesAnExistingVariable() {
        ExecutionContext merged = context(JsonNodeFactory.instance.objectNode().put("a", 1))
                .with(Map.of("a", JsonNodeFactory.instance.numberNode(9)));

        assertEquals(9, merged.variable("a").asInt());
    }

    @Test
    void movingToAnotherStepCarriesTheVariables() {
        ExecutionContext moved = context(JsonNodeFactory.instance.objectNode().put("a", 1))
                .at(StepId.of("validate"));

        assertEquals(StepId.of("validate"), moved.currentStep());
        assertEquals(1, moved.variable("a").asInt());
    }

    @Test
    void reportsAMissingVariableRatherThanFailing() {
        assertTrue(context(null).variable("absent").isMissingNode());
    }
}
