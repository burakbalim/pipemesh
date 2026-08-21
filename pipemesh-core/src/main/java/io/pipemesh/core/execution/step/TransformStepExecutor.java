package io.pipemesh.core.execution.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.execution.ExecutionContext;
import io.pipemesh.core.execution.StepExecutor;
import io.pipemesh.core.execution.StepResult;
import io.pipemesh.core.expression.ExpressionException;
import io.pipemesh.core.expression.JsonPath;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rearranges what is already there, without asking anyone (§9.6).
 *
 * <pre>
 * { "type": "transform", "operation": "merge",
 *   "inputs": ["$.venues", "$.preferences"], "output": "context", "next": "recommend" }
 * </pre>
 *
 * <p>Three operations, and deliberately no more. A transformation that needs
 * arithmetic, conditionals or a function call is a business rule, and a business
 * rule belongs in a capability where it can be tested, versioned and owned — not
 * in a string inside a workflow (§23.1). The same objection that keeps the
 * condition language small keeps this one small.
 */
public final class TransformStepExecutor implements StepExecutor {

    private static final String OPERATION = "operation";
    private static final String INPUTS = "inputs";
    private static final String OUTPUT = "output";
    private static final String NEXT = "next";

    private static final JsonNode SCHEMA = StepSchemas.parse("""
            {
              "properties": {
                "operation": {"type": "string", "enum": ["merge", "pick", "collect"]},
                "inputs":    {"type": "array", "items": {"type": "string"}},
                "output":    {"type": "string"},
                "next":      {"type": "string"}
              },
              "required": ["operation", "inputs", "output", "next"]
            }
            """);

    @Override
    public boolean supports(StepType type) {
        return StepType.of("transform").equals(type);
    }

    @Override
    public Optional<JsonNode> configSchema() {
        return Optional.of(SCHEMA);
    }

    @Override
    public List<StepId> outgoing(Step step) {
        return Stepwiring.stepIds(step, NEXT);
    }

    @Override
    public StepResult execute(Step step, ExecutionContext context) {
        JsonNode config = step.config();
        String operation = config.path(OPERATION).asText("");

        List<JsonNode> inputs;
        try {
            inputs = read(config, context);
        } catch (ExpressionException badPath) {
            return new StepResult.Failed("transform.bad_input", badPath.getMessage(), false);
        }

        JsonNode result = switch (operation) {
            case "merge" -> merge(inputs);
            case "pick" -> pick(inputs);
            case "collect" -> collect(inputs);
            default -> null;
        };

        if (result == null) {
            return new StepResult.Failed("transform.unknown_operation",
                    "'" + operation + "' is not something a transform can do", false);
        }

        return new StepResult.Continue(
                StepId.of(config.path(NEXT).asText()),
                Map.of(config.path(OUTPUT).asText(), result));
    }

    private List<JsonNode> read(JsonNode config, ExecutionContext context) {
        List<JsonNode> values = new ArrayList<>();
        config.path(INPUTS).forEach(input ->
                values.add(JsonPath.parse(input.asText()).read(context.variables())));
        return values;
    }

    /** Later inputs win, which is the only ordering a list can mean. */
    private JsonNode merge(List<JsonNode> inputs) {
        ObjectNode merged = JsonNodeFactory.instance.objectNode();
        for (JsonNode input : inputs) {
            if (input.isObject()) {
                input.fields().forEachRemaining(field -> merged.set(field.getKey(), field.getValue()));
            }
        }
        return merged;
    }

    /**
     * The first input that is actually there.
     *
     * <p>For the workflow that has two ways of learning something and a preference
     * between them.
     */
    private JsonNode pick(List<JsonNode> inputs) {
        return inputs.stream()
                .filter(input -> !input.isMissingNode() && !input.isNull())
                .findFirst()
                .orElseGet(() -> JsonNodeFactory.instance.nullNode());
    }

    /** Everything, in the order asked for, absent values included as null. */
    private JsonNode collect(List<JsonNode> inputs) {
        ArrayNode collected = JsonNodeFactory.instance.arrayNode();
        inputs.forEach(input ->
                collected.add(input.isMissingNode() ? JsonNodeFactory.instance.nullNode() : input));
        return collected;
    }
}
