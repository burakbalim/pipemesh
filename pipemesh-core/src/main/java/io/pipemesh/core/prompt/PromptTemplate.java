package io.pipemesh.core.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import io.pipemesh.core.expression.JsonPath;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt text with holes for execution variables: {@code {{$.input.message}}}.
 *
 * <p>Substitution and nothing else — no conditionals, no loops, no expressions.
 * A template engine inside a prompt is a second programming language nobody
 * chose, and logic that grows there is logic outside every test the workflow has.
 *
 * <p>A placeholder whose path is missing renders as empty rather than failing:
 * an absent optional field should not take down a run.
 */
public record PromptTemplate(PromptId id, String text) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(\\$[^}\\s]*)\\s*}}");

    public PromptTemplate {
        Objects.requireNonNull(id, "prompt id");
        Objects.requireNonNull(text, "prompt text");
    }

    public String render(JsonNode variables) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value(matcher.group(1), variables)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private String value(String path, JsonNode variables) {
        JsonNode found = JsonPath.parse(path).read(variables);
        if (found.isMissingNode() || found.isNull()) {
            return "";
        }
        return found.isValueNode() ? found.asText() : found.toString();
    }
}
