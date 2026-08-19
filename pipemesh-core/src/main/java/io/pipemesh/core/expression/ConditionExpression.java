package io.pipemesh.core.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A condition: one comparison between two operands, each either a path or a literal.
 *
 * <pre>
 *   $.request.valid == true
 *   $.event.price &gt; 0
 *   $.user.tier == 'gold'
 * </pre>
 *
 * <p>There is no {@code and}, no {@code or}, no arithmetic and no function call —
 * on purpose. A condition that needs them is business logic, and business logic
 * belongs in a capability rather than in a string inside a workflow (§23.1). The
 * grammar is small enough to keep evaluation deterministic and side-effect free.
 */
public final class ConditionExpression {

    private final JsonPath left;
    private final JsonNode leftLiteral;
    private final Comparison comparison;
    private final JsonPath right;
    private final JsonNode rightLiteral;

    private ConditionExpression(Operand left, Comparison comparison, Operand right) {
        this.left = left.path();
        this.leftLiteral = left.literal();
        this.comparison = comparison;
        this.right = right.path();
        this.rightLiteral = right.literal();
    }

    public static ConditionExpression parse(String expression) {
        Objects.requireNonNull(expression, "expression");
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            throw new ExpressionException("condition must not be blank");
        }

        for (Comparison comparison : Comparison.byMatchPrecedence()) {
            int at = indexOfOperator(trimmed, comparison.symbol());
            if (at < 0) {
                continue;
            }
            String left = trimmed.substring(0, at).trim();
            String right = trimmed.substring(at + comparison.symbol().length()).trim();
            return new ConditionExpression(Operand.parse(left), comparison, Operand.parse(right));
        }
        throw new ExpressionException("condition has no comparison operator: " + expression);
    }

    public boolean evaluate(JsonNode variables) {
        JsonNode leftValue = left != null ? left.read(variables) : leftLiteral;
        JsonNode rightValue = right != null ? right.read(variables) : rightLiteral;
        return comparison.test(leftValue, rightValue);
    }

    /** Ignores an operator that appears inside a quoted literal. */
    private static int indexOfOperator(String expression, String symbol) {
        char quote = 0;
        for (int i = 0; i <= expression.length() - symbol.length(); i++) {
            char current = expression.charAt(i);
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
                continue;
            }
            if (expression.startsWith(symbol, i)) {
                return i;
            }
        }
        return -1;
    }

    private record Operand(JsonPath path, JsonNode literal) {

        static Operand parse(String token) {
            if (token.isEmpty()) {
                throw new ExpressionException("condition is missing an operand");
            }
            if (JsonPath.isPath(token)) {
                return new Operand(JsonPath.parse(token), null);
            }
            return new Operand(null, literal(token));
        }

        private static JsonNode literal(String token) {
            if (isQuoted(token)) {
                return TextNode.valueOf(token.substring(1, token.length() - 1));
            }
            return switch (token) {
                case "true" -> BooleanNode.TRUE;
                case "false" -> BooleanNode.FALSE;
                case "null" -> NullNode.getInstance();
                default -> number(token);
            };
        }

        private static boolean isQuoted(String token) {
            return token.length() >= 2
                    && (token.charAt(0) == '\'' || token.charAt(0) == '"')
                    && token.charAt(token.length() - 1) == token.charAt(0);
        }

        private static JsonNode number(String token) {
            try {
                return DecimalNode.valueOf(new BigDecimal(token));
            } catch (NumberFormatException notANumber) {
                throw new ExpressionException("operand is neither a path nor a literal: " + token);
            }
        }
    }
}
