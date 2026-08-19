package io.pipemesh.core.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * The read half of the expression language: {@code $.event.venues[0].name}.
 *
 * <p>Reads only. No functions, no filters, no wildcards — a condition must be a
 * deterministic look-up plus a comparison, and every feature beyond that is a
 * way for logic to migrate out of the service layer and into a string (§23.1).
 */
public final class JsonPath {

    private final String expression;

    private JsonPath(String expression) {
        this.expression = expression;
    }

    public static boolean isPath(String token) {
        return token.startsWith("$");
    }

    public static JsonPath parse(String expression) {
        if (!isPath(expression)) {
            throw new ExpressionException("path must start with '$': " + expression);
        }
        return new JsonPath(expression);
    }

    /** Returns {@link MissingNode} when any segment is absent — an absent value is not an error. */
    public JsonNode read(JsonNode root) {
        JsonNode current = root;
        for (String segment : segments()) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return MissingNode.getInstance();
            }
            current = readSegment(current, segment);
        }
        return current == null ? MissingNode.getInstance() : current;
    }

    private String[] segments() {
        String body = expression.substring(1);
        if (body.startsWith(".")) {
            body = body.substring(1);
        }
        if (body.isEmpty()) {
            return new String[0];
        }
        return body.replace("[", ".[").split("\\.");
    }

    private JsonNode readSegment(JsonNode current, String segment) {
        if (segment.isEmpty()) {
            throw new ExpressionException("empty segment in path: " + expression);
        }
        if (segment.startsWith("[")) {
            return current.path(arrayIndex(segment));
        }
        return current.path(segment);
    }

    private int arrayIndex(String segment) {
        if (!segment.endsWith("]")) {
            throw new ExpressionException("unterminated index in path: " + expression);
        }
        String index = segment.substring(1, segment.length() - 1);
        try {
            return Integer.parseInt(index);
        } catch (NumberFormatException notAnIndex) {
            throw new ExpressionException("array index must be a number: " + segment);
        }
    }

    @Override
    public String toString() {
        return expression;
    }
}
