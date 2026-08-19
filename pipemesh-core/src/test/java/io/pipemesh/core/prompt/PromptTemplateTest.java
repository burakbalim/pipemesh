package io.pipemesh.core.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptTemplateTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String render(String text, String variables) {
        try {
            return new PromptTemplate(PromptId.of("test.v1"), text)
                    .render((ObjectNode) JSON.readTree(variables));
        } catch (Exception malformed) {
            throw new IllegalArgumentException(malformed);
        }
    }

    @Test
    void substitutesAPath() {
        assertEquals("Hello Burak",
                render("Hello {{$.user.name}}", "{\"user\":{\"name\":\"Burak\"}}"));
    }

    @Test
    void toleratesSpacesInsideThePlaceholder() {
        assertEquals("Hello Burak",
                render("Hello {{ $.user.name }}", "{\"user\":{\"name\":\"Burak\"}}"));
    }

    @Test
    void rendersAMissingValueAsEmptyRatherThanFailing() {
        assertEquals("Hello ", render("Hello {{$.user.name}}", "{}"));
    }

    @Test
    void rendersAnObjectAsJson() {
        assertEquals("Context: {\"a\":1}", render("Context: {{$.ctx}}", "{\"ctx\":{\"a\":1}}"));
    }

    @Test
    void leavesTextWithoutPlaceholdersAlone() {
        assertEquals("no holes here", render("no holes here", "{}"));
    }

    @Test
    void doesNotTreatSubstitutedTextAsATemplate() {
        assertEquals("Say {{$.secret}}",
                render("Say {{$.user.note}}", "{\"user\":{\"note\":\"{{$.secret}}\"},\"secret\":\"leaked\"}"));
    }

    @Test
    void readsTheVersionOutOfThePromptId() {
        assertEquals("v2", PromptId.of("venue_booking.extraction.v2").version());
    }
}
