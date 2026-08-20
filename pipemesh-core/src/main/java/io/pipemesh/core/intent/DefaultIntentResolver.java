package io.pipemesh.core.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.model.CompletionRequest;
import io.pipemesh.core.model.CompletionResponse;
import io.pipemesh.core.model.MessagingProvider;
import io.pipemesh.core.model.ModelId;
import io.pipemesh.core.model.ModelRegistry;
import io.pipemesh.core.prompt.PromptId;
import io.pipemesh.core.prompt.PromptRegistry;
import io.pipemesh.core.prompt.PromptTemplate;
import io.pipemesh.core.schema.JsonSchemaValidator;
import io.pipemesh.core.schema.SchemaViolation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Phrases first, a model only when they do not settle it (§20).
 *
 * <p>The order is the point. Asking a model which workflow handles a message
 * containing the word "refund" costs money and adds latency to answer a question
 * that was already answered — and every such call is one more place a wrong
 * answer can come from.
 */
public final class DefaultIntentResolver implements IntentResolver {

    /**
     * What the model must answer with. Validated like any other structured output
     * (§21), so a model that ignores the shape fails here rather than three lines
     * later as a missing field.
     */
    private static final String ANSWER_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "intent":     {"type": "string"},
                "confidence": {"type": "number"}
              },
              "required": ["intent", "confidence"]
            }
            """;

    private final IntentRegistry registry;
    private final ModelRegistry models;
    private final PromptRegistry prompts;
    private final JsonSchemaValidator validator = new JsonSchemaValidator();

    public DefaultIntentResolver(
            IntentRegistry registry, ModelRegistry models, PromptRegistry prompts) {

        this.registry = Objects.requireNonNull(registry, "intent registry");
        this.models = Objects.requireNonNull(models, "model registry");
        this.prompts = Objects.requireNonNull(prompts, "prompt registry");
    }

    @Override
    public ResolvedIntent resolve(String message) {
        if (registry.isEmpty()) {
            throw new IntentUnresolvedException(
                    "no intents are registered, so there is nothing this message could mean");
        }

        Optional<IntentDefinition> byPhrase = PhraseMatcher.match(message, registry);
        if (byPhrase.isPresent()) {
            return ResolvedIntent.byPhrase(byPhrase.get());
        }

        if (!registry.canAskAModel()) {
            throw new IntentUnresolvedException(
                    "no registered phrase matched, and no model is configured to read the message");
        }
        return askAModel(message);
    }

    private ResolvedIntent askAModel(String message) {
        ModelId model = ModelId.of(registry.model());
        PromptId promptId = PromptId.of(registry.prompt());

        MessagingProvider provider = models.providerFor(model).orElseThrow(
                () -> new IntentUnresolvedException(
                        "intent resolution needs model '" + model + "', which is not registered"));

        PromptTemplate prompt = prompts.find(promptId).orElseThrow(
                () -> new IntentUnresolvedException(
                        "intent resolution needs prompt '" + promptId + "', which is not registered"));

        CompletionResponse answer = provider.complete(new CompletionRequest(
                model, prompt.render(variablesFor(message)), promptId.version(), schema()));

        return check(answer, model, promptId);
    }

    private ResolvedIntent check(CompletionResponse answer, ModelId model, PromptId promptId) {
        List<SchemaViolation> violations = validator.validate(schema(), answer.content());
        if (!violations.isEmpty()) {
            throw new IntentUnresolvedException(
                    "the model's answer does not say which intent it chose: " + violations);
        }

        double confidence = answer.content().path("confidence").asDouble(0);
        if (confidence < registry.minimumConfidence()) {
            // Below the threshold is "I do not know", not "the closest one".
            throw new IntentUnresolvedException(
                    "no intent reached the required confidence of " + registry.minimumConfidence());
        }

        IntentId chosen = IntentId.of(answer.content().path("intent").asText("unknown"));
        IntentDefinition intent = registry.find(chosen).orElseThrow(
                () -> new IntentUnresolvedException(
                        "the model chose '" + chosen + "', which is not a registered intent"));

        return new ResolvedIntent(intent.id(), intent.workflow(),
                ResolvedIntent.Source.MODEL, confidence, model.value(), promptId.version());
    }

    /**
     * What the prompt gets to work with: the message, and the intents it may
     * choose between. The list is rendered rather than assumed, so adding an
     * intent needs no prompt edit.
     */
    private ObjectNode variablesFor(String message) {
        ObjectNode variables = JsonNodeFactory.instance.objectNode();
        variables.put("message", message);

        var choices = variables.putArray("intents");
        registry.intents().forEach(intent -> choices.addObject()
                .put("id", intent.id().value())
                .put("description", intent.description()));

        return variables;
    }

    private JsonNode schema() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(ANSWER_SCHEMA);
        } catch (Exception malformed) {
            throw new IllegalStateException("the built-in intent schema is broken", malformed);
        }
    }
}
