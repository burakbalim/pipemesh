package io.pipemesh.core.execution.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.pipemesh.core.capability.CapabilityCall;
import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityId;
import io.pipemesh.core.capability.CapabilityProvider;
import io.pipemesh.core.capability.CapabilityInvoker;
import io.pipemesh.core.capability.CapabilityRegistry;
import io.pipemesh.core.capability.CapabilityResult;
import io.pipemesh.core.execution.ExecutionContext;
import io.pipemesh.core.execution.StepAttributes;
import io.pipemesh.core.execution.StepExecutor;
import io.pipemesh.core.execution.StepResult;
import io.pipemesh.core.expression.JsonPath;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Invokes a capability by name (§9.2).
 *
 * <pre>
 * { "type": "capability", "capability": "venue_search",
 *   "input": "$.request.location", "output": "venues", "next": "approval" }
 * </pre>
 *
 * <p>Note what the step does not say: whether {@code venue_search} is an MCP
 * tool, a REST endpoint, a gRPC service or a function in someone's application.
 * The registry knows; the workflow does not, and adding a fifth kind changes
 * neither this class nor any workflow (§9.8).
 *
 * <p>This is provider I/O: it runs before anything is persisted.
 */
public final class CapabilityStepExecutor implements StepExecutor {

    private static final JsonNode SCHEMA = StepSchemas.parse("""
            {
              "properties": {
                "capability": {"type": "string"},
                "input":      {"type": "string"},
                "output":     {"type": "string"},
                "next":       {"type": "string"}
              },
              "required": ["capability"]
            }
            """);

    private static final String CAPABILITY = "capability";
    private static final String INPUT = "input";
    private static final String OUTPUT = "output";
    private static final String NEXT = "next";

    private final CapabilityInvoker invoker;

    public CapabilityStepExecutor(CapabilityRegistry capabilities, List<CapabilityProvider> providers) {
        this(new CapabilityInvoker(capabilities, providers));
    }

    public CapabilityStepExecutor(CapabilityInvoker invoker) {
        this.invoker = Objects.requireNonNull(invoker, "capability invoker");
    }

    @Override
    public boolean supports(StepType type) {
        return StepType.CAPABILITY.equals(type);
    }

    /** What a capability step may say. Anything else is refused at load time (§23.1). */
    @Override
    public Optional<JsonNode> configSchema() {
        return Optional.of(SCHEMA);
    }

    @Override
    public StepResult execute(Step step, ExecutionContext context) {
        JsonNode config = step.config();
        CapabilityId id = CapabilityId.of(required(config, CAPABILITY));

        CapabilityResult result = invoker.invoke(id, inputFor(config, context), callOf(step, context));

        return switch (result) {
            case CapabilityResult.Success success -> new StepResult.Continue(
                    StepId.of(required(config, NEXT)),
                    Map.of(required(config, OUTPUT), success.output()),
                    attributesOf(id));
            case CapabilityResult.Failure failure -> new StepResult.Failed(
                    failure.code(), failure.message(), failure.retryable(), attributesOf(id));
        };
    }

    @Override
    public List<StepId> outgoing(Step step) {
        return Stepwiring.stepIds(step, NEXT);
    }

    /**
     * A capability that declared itself non-idempotent cannot be replayed after a
     * crash: the call may have landed before the process died, and nothing on this
     * side knows. The execution stops for a person rather than charging a card
     * twice.
     */
    @Override
    public boolean repeatable(Step step, ExecutionContext context) {
        String id = step.config().path(CAPABILITY).asText("");
        if (id.isBlank()) {
            return true;
        }
        return invoker.find(CapabilityId.of(id))
                .map(CapabilityDescriptor::idempotent)
                .orElse(true);
    }

    private CapabilityCall callOf(Step step, ExecutionContext context) {
        return new CapabilityCall(
                context.organization(), context.executionId(), step.id(),
                context.traceParent(), context.principal());
    }

    /** An absent {@code input} means the capability takes the whole context. */
    private JsonNode inputFor(JsonNode config, ExecutionContext context) {
        String input = config.path(INPUT).asText("");
        if (input.isBlank()) {
            return context.variables();
        }
        return JsonPath.parse(input).read(context.variables());
    }

    private Map<String, JsonNode> attributesOf(CapabilityId id) {
        JsonNodeFactory json = JsonNodeFactory.instance;
        return invoker.find(id)
                .map(capability -> Map.of(
                        StepAttributes.CAPABILITY_ID, (JsonNode) json.textNode(id.value()),
                        StepAttributes.CAPABILITY_EXECUTION_TYPE,
                        json.textNode(capability.executionType())))
                .orElseGet(() -> Map.of(StepAttributes.CAPABILITY_ID, json.textNode(id.value())));
    }

    private String required(JsonNode config, String field) {
        String value = config.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("capability step is missing '" + field + "'");
        }
        return value;
    }
}
