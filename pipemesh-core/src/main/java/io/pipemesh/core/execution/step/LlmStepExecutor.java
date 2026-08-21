package io.pipemesh.core.execution.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.pipemesh.core.execution.ExecutionContext;
import io.pipemesh.core.execution.StepAttributes;
import io.pipemesh.core.execution.StepExecutor;
import io.pipemesh.core.execution.StepResult;
import io.pipemesh.core.observability.CompositeExecutionObserver;
import io.pipemesh.core.observability.ExecutionObserver;
import io.pipemesh.core.observability.TokenEvent;
import io.pipemesh.core.model.CompletionRequest;
import io.pipemesh.core.model.CompletionResponse;
import io.pipemesh.core.model.MessagingProvider;
import io.pipemesh.core.model.ModelId;
import io.pipemesh.core.model.ModelRegistry;
import io.pipemesh.core.prompt.PromptId;
import io.pipemesh.core.prompt.PromptRegistry;
import io.pipemesh.core.prompt.PromptTemplate;
import io.pipemesh.core.schema.JsonSchemaValidator;
import io.pipemesh.core.schema.SchemaRegistry;
import io.pipemesh.core.schema.SchemaViolation;
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

    private static final JsonNode SCHEMA = StepSchemas.parse("""
            {
              "properties": {
                "model":        {"type": "string"},
                "prompt":       {"type": "string"},
                "output":       {"type": "string"},
                "next":         {"type": "string"},
                "stream":       {"type": "boolean"},
                "outputSchema": {}
              },
              "required": ["model", "prompt", "output", "next"]
            }
            """);

    private static final String MODEL = "model";
    private static final String PROMPT = "prompt";
    private static final String OUTPUT = "output";
    private static final String NEXT = "next";
    private static final String OUTPUT_SCHEMA = "outputSchema";
    private static final String STREAM = "stream";

    private final ModelRegistry models;
    private final PromptRegistry prompts;
    private final SchemaRegistry schemas;
    private final ExecutionObserver observer;
    private final JsonSchemaValidator validator = new JsonSchemaValidator();

    public LlmStepExecutor(ModelRegistry models, PromptRegistry prompts) {
        this(models, prompts, schemaId -> Optional.empty(), ExecutionObserver.NONE);
    }

    public LlmStepExecutor(ModelRegistry models, PromptRegistry prompts, SchemaRegistry schemas) {
        this(models, prompts, schemas, ExecutionObserver.NONE);
    }

    /**
     * @param observer where tokens go while the step is still running. The engine
     *                 has its own reference to the same observer for execution
     *                 events; this one exists because tokens are produced inside
     *                 the step, before there is any result to report.
     */
    public LlmStepExecutor(
            ModelRegistry models, PromptRegistry prompts, SchemaRegistry schemas,
            ExecutionObserver observer) {

        this.models = Objects.requireNonNull(models, "model registry");
        this.prompts = Objects.requireNonNull(prompts, "prompt registry");
        this.schemas = Objects.requireNonNull(schemas, "schema registry");
        this.observer = CompositeExecutionObserver.guarded(
                Objects.requireNonNull(observer, "observer"));
    }

    @Override
    public boolean supports(StepType type) {
        return StepType.LLM.equals(type);
    }


    /** What a llm step may say. Anything else is refused at load time (§23.1). */
    @Override
    public Optional<JsonNode> configSchema() {
        return Optional.of(SCHEMA);
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

        JsonNode schema;
        try {
            schema = schemaFor(config);
        } catch (IllegalStateException unknownSchema) {
            return new StepResult.Failed("llm.unknown_schema", unknownSchema.getMessage(), false);
        }

        CompletionRequest request = new CompletionRequest(
                model,
                prompt.get().render(context.variables()),
                promptId.version(),
                schema);

        CompletionResponse response = answer(step, provider.get(), request, context);
        Map<String, JsonNode> attributes = attributesOf(model, promptId, response);

        List<SchemaViolation> violations = schema == null
                ? List.of()
                : validator.validate(schema, response.content());

        if (!violations.isEmpty()) {
            // Retryable: a model that ignored the shape once will often honour it
            // on a second pass, and the retry policy decides whether to spend one.
            return new StepResult.Failed("llm.schema_violation",
                    "the model's answer does not match '" + schemaName(config) + "': " + violations,
                    true, attributes);
        }

        return new StepResult.Continue(
                StepId.of(required(config, NEXT)),
                Map.of(required(config, OUTPUT), response.content()),
                attributes);
    }

    /**
     * Streams when the step asked for it, and calls straight through otherwise.
     *
     * <p>Either way the step ends with a complete answer, so everything after this
     * point — schema validation, the variable it writes — is unchanged by the
     * choice. Streaming is how the answer arrives, not what it is.
     */
    private CompletionResponse answer(
            Step step, MessagingProvider provider, CompletionRequest request, ExecutionContext context) {

        if (!step.config().path(STREAM).asBoolean(false)) {
            return provider.complete(request);
        }
        return provider.stream(request, chunk -> observer.tokenProduced(new TokenEvent(
                context.executionId(),
                context.organization(),
                step.id(),
                chunk.text(),
                chunk.index())));
    }

    /**
     * A step may name a schema or carry one inline.
     *
     * <p>Naming is what the examples do: a schema is a shared artifact, and two
     * workflows extracting the same shape should not each own a copy that drifts
     * (§24).
     */
    private JsonNode schemaFor(JsonNode config) {
        JsonNode declared = config.get(OUTPUT_SCHEMA);
        if (declared == null || declared.isNull()) {
            return null;
        }
        if (declared.isObject()) {
            return declared;
        }
        String schemaId = declared.asText("");
        return schemas.find(schemaId).orElseThrow(() -> new IllegalStateException(
                "no schema registered as '" + schemaId + "'"));
    }

    private String schemaName(JsonNode config) {
        JsonNode declared = config.get(OUTPUT_SCHEMA);
        return declared != null && declared.isTextual() ? declared.asText() : "the declared schema";
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
