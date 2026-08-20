package io.pipemesh.core.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks a value against a deliberately small subset of JSON Schema.
 *
 * <pre>
 * type        object · array · string · number · integer · boolean · null
 * properties  recursively
 * required
 * items
 * enum
 * </pre>
 *
 * <p>No {@code $ref}, no {@code allOf}/{@code anyOf}/{@code oneOf}, no patterns,
 * formats or numeric bounds. Those need a library, and every dependency this
 * runtime carries is one its embedders inherit. Model-output schemas sit inside
 * this subset in practice; a need beyond it should arrive as its own module
 * rather than as a dependency in core.
 *
 * <p>A schema that uses an unsupported keyword is not rejected — the keyword is
 * ignored. Failing a valid answer because the validator is small would be worse
 * than checking less than the schema asked for.
 */
public final class JsonSchemaValidator {

    private static final String ROOT = "$";

    public List<SchemaViolation> validate(JsonNode schema, JsonNode value) {
        List<SchemaViolation> violations = new ArrayList<>();
        check(ROOT, schema, value, violations);
        return List.copyOf(violations);
    }

    public boolean matches(JsonNode schema, JsonNode value) {
        return validate(schema, value).isEmpty();
    }

    private void check(String path, JsonNode schema, JsonNode value, List<SchemaViolation> violations) {
        if (schema == null || !schema.isObject()) {
            return;
        }
        if (value == null || value.isMissingNode()) {
            violations.add(new SchemaViolation(path, "is missing"));
            return;
        }
        if (!checkType(path, schema, value, violations)) {
            return;
        }
        checkEnum(path, schema, value, violations);
        checkRequired(path, schema, value, violations);
        checkProperties(path, schema, value, violations);
        checkItems(path, schema, value, violations);
    }

    private boolean checkType(
            String path, JsonNode schema, JsonNode value, List<SchemaViolation> violations) {

        String expected = schema.path("type").asText("");
        if (expected.isBlank() || hasType(expected, value)) {
            return true;
        }
        violations.add(new SchemaViolation(path,
                "expected " + expected + ", got " + actualType(value)));
        return false;
    }

    private boolean hasType(String expected, JsonNode value) {
        return switch (expected) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private String actualType(JsonNode value) {
        return value.getNodeType().toString().toLowerCase();
    }

    private void checkEnum(
            String path, JsonNode schema, JsonNode value, List<SchemaViolation> violations) {

        JsonNode allowed = schema.path("enum");
        if (!allowed.isArray()) {
            return;
        }
        for (JsonNode candidate : allowed) {
            if (candidate.equals(value)) {
                return;
            }
        }
        violations.add(new SchemaViolation(path, "is not one of " + allowed));
    }

    private void checkRequired(
            String path, JsonNode schema, JsonNode value, List<SchemaViolation> violations) {

        if (!value.isObject()) {
            return;
        }
        for (JsonNode required : schema.path("required")) {
            String field = required.asText();
            if (!value.has(field)) {
                violations.add(new SchemaViolation(child(path, field), "is required"));
            }
        }
    }

    private void checkProperties(
            String path, JsonNode schema, JsonNode value, List<SchemaViolation> violations) {

        JsonNode properties = schema.path("properties");
        if (!properties.isObject() || !value.isObject()) {
            return;
        }
        properties.fields().forEachRemaining(property -> {
            if (value.has(property.getKey())) {
                check(child(path, property.getKey()), property.getValue(),
                        value.get(property.getKey()), violations);
            }
        });
    }

    private void checkItems(
            String path, JsonNode schema, JsonNode value, List<SchemaViolation> violations) {

        JsonNode items = schema.path("items");
        if (!items.isObject() || !value.isArray()) {
            return;
        }
        for (int index = 0; index < value.size(); index++) {
            check(path + "[" + index + "]", items, value.get(index), violations);
        }
    }

    private String child(String path, String field) {
        return path + "." + field;
    }
}
