package io.pipemesh.core.execution.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityId;
import io.pipemesh.core.capability.CapabilityProvider;
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
import java.util.stream.Collectors;

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

    private static final String CAPABILITY = "capability";
    private static final String INPUT = "input";
    private static final String OUTPUT = "output";
    private static final String NEXT = "next";

    private final CapabilityRegistry capabilities;
    private final Map<String, CapabilityProvider> providers;

    public CapabilityStepExecutor(CapabilityRegistry capabilities, List<CapabilityProvider> providers) {
        this.capabilities = Objects.requireNonNull(capabilities, "capability registry");
        this.providers = providers.stream().collect(
                Collectors.toUnmodifiableMap(CapabilityProvider::type, provider -> provider));
    }

    @Override
    public boolean supports(StepType type) {
        return StepType.CAPABILITY.equals(type);
    }

    @Override
    public StepResult execute(Step step, ExecutionContext context) {
        JsonNode config = step.config();
        CapabilityId id = CapabilityId.of(required(config, CAPABILITY));

        Optional<CapabilityDescriptor> capability = capabilities.find(id);
        if (capability.isEmpty()) {
            return new StepResult.Failed("capability.unknown",
                    "no capability registered as '" + id + "'", false);
        }
        CapabilityDescriptor descriptor = capability.get();

        CapabilityProvider provider = providers.get(descriptor.executionType());
        if (provider == null) {
            return new StepResult.Failed("capability.no_provider",
                    "capability '" + id + "' needs a '" + descriptor.executionType()
                            + "' provider, which is not registered", false);
        }

        CapabilityResult result = provider.invoke(descriptor, inputFor(config, context));
        return switch (result) {
            case CapabilityResult.Success success -> new StepResult.Continue(
                    StepId.of(required(config, NEXT)),
                    Map.of(required(config, OUTPUT), success.output()),
                    attributesOf(descriptor));
            case CapabilityResult.Failure failure -> new StepResult.Failed(
                    failure.code(), failure.message(), failure.retryable(), attributesOf(descriptor));
        };
    }

    @Override
    public List<StepId> outgoing(Step step) {
        return Stepwiring.stepIds(step, NEXT);
    }

    /** An absent {@code input} means the capability takes the whole context. */
    private JsonNode inputFor(JsonNode config, ExecutionContext context) {
        String input = config.path(INPUT).asText("");
        if (input.isBlank()) {
            return context.variables();
        }
        return JsonPath.parse(input).read(context.variables());
    }

    private Map<String, JsonNode> attributesOf(CapabilityDescriptor descriptor) {
        JsonNodeFactory json = JsonNodeFactory.instance;
        return Map.of(
                StepAttributes.CAPABILITY_ID, json.textNode(descriptor.id().value()),
                StepAttributes.CAPABILITY_EXECUTION_TYPE, json.textNode(descriptor.executionType()));
    }

    private String required(JsonNode config, String field) {
        String value = config.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("capability step is missing '" + field + "'");
        }
        return value;
    }
}
