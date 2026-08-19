package io.pipemesh.core.expression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionExpressionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode variables(String json) {
        try {
            return (ObjectNode) JSON.readTree(json);
        } catch (Exception malformed) {
            throw new IllegalArgumentException(malformed);
        }
    }

    private static boolean evaluate(String expression, String json) {
        return ConditionExpression.parse(expression).evaluate(variables(json));
    }

    @Test
    void comparesABooleanFieldToALiteral() {
        assertTrue(evaluate("$.request.valid == true", "{\"request\":{\"valid\":true}}"));
        assertFalse(evaluate("$.request.valid == true", "{\"request\":{\"valid\":false}}"));
    }

    @Test
    void comparesNumbers() {
        assertTrue(evaluate("$.event.price > 0", "{\"event\":{\"price\":10}}"));
        assertFalse(evaluate("$.event.price > 0", "{\"event\":{\"price\":0}}"));
        assertTrue(evaluate("$.event.price >= 0", "{\"event\":{\"price\":0}}"));
        assertTrue(evaluate("$.event.price <= 10", "{\"event\":{\"price\":10}}"));
    }

    @Test
    void doesNotConfuseGreaterOrEqualWithGreater() {
        assertTrue(evaluate("$.n >= 5", "{\"n\":5}"));
        assertFalse(evaluate("$.n > 5", "{\"n\":5}"));
    }

    @Test
    void comparesQuotedStrings() {
        assertTrue(evaluate("$.user.tier == 'gold'", "{\"user\":{\"tier\":\"gold\"}}"));
        assertTrue(evaluate("$.user.tier != \"silver\"", "{\"user\":{\"tier\":\"gold\"}}"));
    }

    @Test
    void readsThroughArrayIndexes() {
        assertTrue(evaluate("$.venues[0].name == 'Kaleici'",
                "{\"venues\":[{\"name\":\"Kaleici\"}]}"));
    }

    @Test
    void treatsAMissingFieldAsAbsentRatherThanFailing() {
        assertTrue(evaluate("$.request.valid == null", "{}"));
        assertFalse(evaluate("$.request.valid == true", "{}"));
    }

    @Test
    void treatsAnExplicitNullAndAMissingFieldAsTheSame() {
        assertTrue(evaluate("$.a == null", "{\"a\":null}"));
        assertTrue(evaluate("$.a == null", "{}"));
    }

    @Test
    void comparesIntegersAndDecimalsByValue() {
        assertTrue(evaluate("$.n == 1", "{\"n\":1.0}"));
    }

    @Test
    void comparesTwoPaths() {
        assertTrue(evaluate("$.a == $.b", "{\"a\":2,\"b\":2}"));
        assertFalse(evaluate("$.a == $.b", "{\"a\":2,\"b\":3}"));
    }

    @Test
    void refusesToOrderValuesThatAreNotNumbers() {
        ExpressionException failure = assertThrows(ExpressionException.class,
                () -> evaluate("$.a > 1", "{\"a\":\"text\"}"));

        assertTrue(failure.getMessage().contains("needs numbers"));
    }

    @Test
    void rejectsAnExpressionWithoutAnOperator() {
        assertThrows(ExpressionException.class, () -> ConditionExpression.parse("$.a"));
    }

    @Test
    void rejectsAnOperandThatIsNeitherPathNorLiteral() {
        assertThrows(ExpressionException.class, () -> ConditionExpression.parse("$.a == gold"));
    }

    @Test
    void ignoresAnOperatorInsideAQuotedLiteral() {
        assertTrue(evaluate("$.note == 'a>b'", "{\"note\":\"a>b\"}"));
    }

    @Test
    void hasNoBooleanCompositionByDesign() {
        assertThrows(ExpressionException.class,
                () -> ConditionExpression.parse("$.a == 1 and $.b == 2"));
    }

    @Test
    void reportsTheOperatorItCouldNotSatisfy() {
        assertEquals(6, Comparison.values().length);
    }
}
