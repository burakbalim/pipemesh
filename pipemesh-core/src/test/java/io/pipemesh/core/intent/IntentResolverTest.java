package io.pipemesh.core.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pipemesh.core.model.CompletionRequest;
import io.pipemesh.core.model.CompletionResponse;
import io.pipemesh.core.model.InMemoryModelRegistry;
import io.pipemesh.core.model.MessagingProvider;
import io.pipemesh.core.model.ModelId;
import io.pipemesh.core.prompt.InMemoryPromptRegistry;
import io.pipemesh.core.prompt.PromptId;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentResolverTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<IntentDefinition> INTENTS = List.of(
            new IntentDefinition(IntentId.of("book_venue"), WorkflowId.of("venue_booking"),
                    "The user wants to book a venue or organise an event",
                    List.of("book a venue", "mekan ayır", "organise a meetup")),
            new IntentDefinition(IntentId.of("request_refund"), WorkflowId.of("refund_request"),
                    "The user is asking for money back",
                    List.of("refund", "iade")));

    /** Answers with whatever it was handed, and records what it was asked. */
    private static final class ScriptedModel implements MessagingProvider {

        final List<String> prompts = new ArrayList<>();
        private final String answer;

        ScriptedModel(String answer) {
            this.answer = answer;
        }

        @Override
        public String id() {
            return "scripted";
        }

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            prompts.add(request.prompt());
            return new CompletionResponse(parse(answer), "scripted-1", 12, 4, 1);
        }

        private JsonNode parse(String text) {
            try {
                return JSON.readTree(text);
            } catch (Exception notJson) {
                return JSON.getNodeFactory().textNode(text);
            }
        }
    }

    private ScriptedModel model;

    private IntentResolver resolverWith(String modelAnswer, double threshold, boolean withModel) {
        model = new ScriptedModel(modelAnswer);

        InMemoryModelRegistry models = new InMemoryModelRegistry();
        InMemoryPromptRegistry prompts = new InMemoryPromptRegistry();
        if (withModel) {
            models.register(ModelId.of("fast"), model);
            prompts.register(PromptId.of("intent.classify.v1"),
                    "Choose one of {{$.intents}} for: {{$.message}}");
        }

        IntentRegistry registry = new IntentRegistry(
                INTENTS, withModel ? "fast" : "", withModel ? "intent.classify.v1" : "", threshold);

        return new DefaultIntentResolver(registry, models, prompts);
    }

    private IntentResolver resolver(String modelAnswer) {
        return resolverWith(modelAnswer, 0.6, true);
    }

    @Test
    void aRegisteredPhraseSettlesItWithoutAskingAModel() {
        IntentResolver resolver = resolver("{\"intent\":\"book_venue\",\"confidence\":0.9}");

        ResolvedIntent resolved = resolver.resolve("I would like a refund please");

        assertEquals(WorkflowId.of("refund_request"), resolved.workflow());
        assertEquals(ResolvedIntent.Source.DETERMINISTIC, resolved.source());
        assertTrue(model.prompts.isEmpty(), "the model should not have been asked at all");
    }

    @Test
    void matchesPhrasesWhateverTheCase() {
        ResolvedIntent resolved = resolver("{}").resolve("Please REFUND my order");

        assertEquals(ResolvedIntent.Source.DETERMINISTIC, resolved.source());
    }

    @Test
    void doesNotMatchAPhraseInsideAnotherWord() {
        IntentResolver resolver = resolver("{\"intent\":\"book_venue\",\"confidence\":0.9}");

        ResolvedIntent resolved = resolver.resolve("iadesiz bir bilet almak istiyorum");

        assertEquals(ResolvedIntent.Source.MODEL, resolved.source(),
                "'iadesiz' says a refund is not wanted; matching it would start the wrong workflow");
    }

    @Test
    void asksAModelWhenNoPhraseMatches() {
        IntentResolver resolver = resolver("{\"intent\":\"book_venue\",\"confidence\":0.82}");

        ResolvedIntent resolved = resolver.resolve("I need somewhere for eight people on Friday");

        assertEquals(WorkflowId.of("venue_booking"), resolved.workflow());
        assertEquals(ResolvedIntent.Source.MODEL, resolved.source());
        assertEquals(0.82, resolved.confidence(), 0.0001);
    }

    @Test
    void offersTheModelEveryRegisteredIntent() {
        resolver("{\"intent\":\"book_venue\",\"confidence\":0.9}")
                .resolve("something the phrases do not cover");

        String asked = model.prompts.get(0);
        assertTrue(asked.contains("book_venue"), asked);
        assertTrue(asked.contains("request_refund"), "adding an intent should need no prompt edit");
    }

    @Test
    void refusesWhenTheModelIsNotConfidentEnough() {
        IntentResolver resolver = resolver("{\"intent\":\"book_venue\",\"confidence\":0.3}");

        IntentUnresolvedException refused = assertThrows(IntentUnresolvedException.class,
                () -> resolver.resolve("something vague"));

        assertTrue(refused.getMessage().contains("confidence"));
    }

    @Test
    void refusesAnIntentNobodyRegistered() {
        IntentResolver resolver = resolver("{\"intent\":\"order_pizza\",\"confidence\":0.99}");

        IntentUnresolvedException refused = assertThrows(IntentUnresolvedException.class,
                () -> resolver.resolve("something vague"));

        assertTrue(refused.getMessage().contains("order_pizza"),
                "a confident answer about a workflow that does not exist is still not an answer");
    }

    @Test
    void refusesAnAnswerThatIsNotTheShapeItAskedFor() {
        IntentResolver resolver = resolver("I think they want to book a venue");

        assertThrows(IntentUnresolvedException.class, () -> resolver.resolve("something vague"));
    }

    @Test
    void refusesWhenThereIsNoModelToAsk() {
        IntentResolver resolver = resolverWith("{}", 0.6, false);

        IntentUnresolvedException refused = assertThrows(IntentUnresolvedException.class,
                () -> resolver.resolve("something the phrases do not cover"));

        assertTrue(refused.getMessage().contains("no model is configured"));
    }

    @Test
    void refusesWhenNothingIsRegisteredAtAll() {
        IntentResolver resolver = new DefaultIntentResolver(
                IntentRegistry.of(List.of()), new InMemoryModelRegistry(), new InMemoryPromptRegistry());

        assertThrows(IntentUnresolvedException.class, () -> resolver.resolve("book a venue"));
    }

    @Test
    void answersWithAWorkflowAndNothingElse() {
        ResolvedIntent resolved = resolver("{}").resolve("refund please");

        // The resolver's whole vocabulary: which workflow, and how it decided. No
        // step, no branch, no instruction on how to proceed (§37).
        assertEquals(WorkflowId.of("refund_request"), resolved.workflow());
        assertEquals(IntentId.of("request_refund"), resolved.intent());
    }
}
