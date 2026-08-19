package io.pipemesh.core.workflow;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepTest {

    @Test
    void keepsConfigIsolatedFromTheCallersNode() {
        ObjectNode config = JsonNodeFactory.instance.objectNode().put("model", "fast");
        Step step = new Step(StepId.of("extract"), StepType.LLM, config);

        config.put("model", "reasoning");

        assertEquals("fast", step.config().path("model").asText());
    }

    @Test
    void returnsAConfigCopySoCallersCannotMutateIt() {
        Step step = new Step(StepId.of("extract"), StepType.LLM,
                JsonNodeFactory.instance.objectNode().put("model", "fast"));

        ((ObjectNode) step.config()).put("model", "reasoning");

        assertEquals("fast", step.config().path("model").asText());
    }

    @Test
    void toleratesAStepWithoutConfig() {
        Step step = new Step(StepId.of("done"), StepType.TERMINAL, null);

        assertTrue(step.config().isNull());
    }

    @Test
    void rejectsABlankStepId() {
        assertThrows(IllegalArgumentException.class, () -> StepId.of(" "));
    }
}
