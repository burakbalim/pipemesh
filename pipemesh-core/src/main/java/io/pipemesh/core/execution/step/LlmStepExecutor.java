package io.pipemesh.core.execution.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.pipemesh.core.execution.ExecutionContext;
import io.pipemesh.core.execution.StepAttributes;
import io.pipemesh.core.execution.StepExecutor;
import io.pipemesh.core.execution.StepResult;
import io.pipemesh.core.model.CompletionRequest;
import io.pipemesh.core.model.CompletionResponse;
import io.pipemesh.core.model.MessagingProvider;
import io.pipemesh.core.model.ModelId;
import io.pipemesh.core.model.ModelRegistry;
import io.pipemesh.core.prompt.PromptId;
import io.pipemesh.core.prompt.PromptRegistry;
import io.pipemesh.core.prompt.PromptTemplate;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Calls a model and puts its answer into a variable (§9.1).
 *
 * <pre>
 * { "type": "llm", "model": "fast", "prompt": "venue_booking.extraction.v1",
 *   "output": "request", "next": "validate" }
 * </pre>
 *
 * <p>The step names an alias and a prompt id. Which vendor serves {@code fast},
 * which credentials it uses, what its wire format looks like — none of that is
 * visible here or in the workflow (§12, §13).
 *
 * <p>This is provider I/O: it runs before anything is persisted, so a slow model
 * never holds a database transaction open.
 */
public final class LlmStepExecutor implements StepExecutor {

    private static final String MODEL = "model";
    private static final String PROMPT = "prompt";
    private static final String OUTPUT = "output";
    private static final String NEXT = "next";
    private static final String OUTPUT_SCHEMA = "outputSchema";

    private final ModelRegistry models;
    private final PromptRegistry prompts;

    public LlmStepExecutor(ModelRegistry models, PromptRegistry prompts) {
        this.models = Objects.requireNonNull(models, "model registry");
        this.prompts = Objects.requireNonNull(prompts, "prompt registry");
    }

    @Override
    public boolean supports(StepType type) {
        return StepType.LLM.equals(type);
    }

    @Override
    public StepResult execute(Step step, ExecutionContext context) {
        JsonNode config = step.config();

        ModelId model = ModelId.of(required(config, MODEL));
        PromptId promptId = PromptId.of(required(config, PROMPT));

        Optional<MessagingProvider> provider = models.providerFor(model);
        if (provider.isEmpty()) {
            return new StepResult.Failed("llm.unknown_model",
                    "no provider registered for model '" + model + "'", false);
        }
        Optional<PromptTemplate> prompt = prompts.find(promptId);
        if (prompt.isEmpty()) {
            return new StepResult.Failed("llm.unknown_prompt",
                    "no prompt registered as '" + promptId + "'", false);
        }

        CompletionRequest request = new CompletionRequest(
                model,
                prompt.get().render(context.variables()),
                promptId.version(),
                config.has(OUTPUT_SCHEMA) ? config.get(OUTPUT_SCHEMA) : null);

        CompletionResponse response = provider.get().complete(request);

        return new StepResult.Continue(
                StepId.of(required(config, NEXT)),
                Map.of(required(config, OUTPUT), response.content()),
                attributesOf(model, promptId, response));
    }

    @Override
    public List<StepId> outgoing(Step step) {
        return Stepwiring.stepIds(step, NEXT);
    }

    private Map<String, JsonNode> attributesOf(
            ModelId model, PromptId prompt, CompletionResponse response) {

        JsonNodeFactory json = JsonNodeFactory.instance;
        return Map.of(
                StepAttributes.LLM_MODEL, json.textNode(model.value()),
                StepAttributes.LLM_PROMPT_VERSION, json.textNode(prompt.version()),
                StepAttributes.LLM_INPUT_TOKENS, json.numberNode(response.inputTokens()),
                StepAttributes.LLM_OUTPUT_TOKENS, json.numberNode(response.outputTokens()));
    }

    private String required(JsonNode config, String field) {
        String value = config.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("llm step is missing '" + field + "'");
        }
        return value;
    }
}
