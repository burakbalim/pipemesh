package io.pipemesh.core.execution.step;

import com.fasterxml.jackson.databind.JsonNode;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepId;

import java.util.ArrayList;
import java.util.List;

/** Reads step references out of a step's config, skipping the ones not set. */
final class Stepwiring {

    private Stepwiring() {
    }

    static List<StepId> stepIds(Step step, String... fields) {
        JsonNode config = step.config();
        List<StepId> targets = new ArrayList<>();
        for (String field : fields) {
            String value = config.path(field).asText("");
            if (!value.isBlank()) {
                targets.add(StepId.of(value));
            }
        }
        return List.copyOf(targets);
    }
}
