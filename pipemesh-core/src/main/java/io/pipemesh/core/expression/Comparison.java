package io.pipemesh.core.expression;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The complete set of operators a condition may use.
 *
 * <p>Ordering is defined for numbers only. Comparing a string to a number, or
 * ordering two objects, is a mistake worth failing on rather than guessing at.
 */
public enum Comparison {

    EQUAL("=="),
    NOT_EQUAL("!="),
    GREATER_OR_EQUAL(">="),
    LESS_OR_EQUAL("<="),
    GREATER(">"),
    LESS("<");

    /** Longest first, so {@code >=} is never mistaken for {@code >}. */
    private static final List<Comparison> BY_PRECEDENCE = List.of(
            GREATER_OR_EQUAL, LESS_OR_EQUAL, EQUAL, NOT_EQUAL, GREATER, LESS);

    private final String symbol;

    Comparison(String symbol) {
        this.symbol = symbol;
    }

    public static List<Comparison> byMatchPrecedence() {
        return BY_PRECEDENCE;
    }

    public String symbol() {
        return symbol;
    }

    public boolean test(JsonNode left, JsonNode right) {
        return switch (this) {
            case EQUAL -> equalValues(left, right);
            case NOT_EQUAL -> !equalValues(left, right);
            case GREATER -> compareNumbers(left, right) > 0;
            case GREATER_OR_EQUAL -> compareNumbers(left, right) >= 0;
            case LESS -> compareNumbers(left, right) < 0;
            case LESS_OR_EQUAL -> compareNumbers(left, right) <= 0;
        };
    }

    /** A missing value and an explicit null compare equal: both mean "not there". */
    private boolean equalValues(JsonNode left, JsonNode right) {
        if (isAbsent(left) || isAbsent(right)) {
            return isAbsent(left) && isAbsent(right);
        }
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        return left.equals(right);
    }

    private boolean isAbsent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    private int compareNumbers(JsonNode left, JsonNode right) {
        if (!left.isNumber() || !right.isNumber()) {
            throw new ExpressionException(
                    "operator " + symbol + " needs numbers, got " + describe(left) + " and " + describe(right));
        }
        return left.decimalValue().compareTo(right.decimalValue());
    }

    private String describe(JsonNode node) {
        return node.isMissingNode() ? "missing" : node.getNodeType().toString().toLowerCase();
    }
}
