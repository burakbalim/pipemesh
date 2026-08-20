package io.pipemesh.core.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSchemaValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String VENUE_REQUEST = """
            {
              "type": "object",
              "properties": {
                "valid":    {"type": "boolean"},
                "location": {"type": "string"},
                "people":   {"type": "integer"}
              },
              "required": ["valid", "location"]
            }
            """;

    private final JsonSchemaValidator validator = new JsonSchemaValidator();

    private JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception malformed) {
            throw new IllegalArgumentException(malformed);
        }
    }

    private List<SchemaViolation> validate(String schema, String value) {
        return validator.validate(json(schema), json(value));
    }

    @Test
    void acceptsAValueThatMatches() {
        assertTrue(validate(VENUE_REQUEST,
                "{\"valid\":true,\"location\":\"Antalya\",\"people\":6}").isEmpty());
    }

    @Test
    void acceptsAnOptionalFieldBeingAbsent() {
        assertTrue(validate(VENUE_REQUEST, "{\"valid\":true,\"location\":\"Antalya\"}").isEmpty());
    }

    @Test
    void namesTheFieldThatIsMissing() {
        List<SchemaViolation> violations = validate(VENUE_REQUEST, "{\"valid\":true}");

        assertEquals(1, violations.size());
        assertEquals("$.location", violations.get(0).path());
        assertTrue(violations.get(0).message().contains("required"));
    }

    @Test
    void namesTheFieldWithTheWrongType() {
        List<SchemaViolation> violations =
                validate(VENUE_REQUEST, "{\"valid\":true,\"location\":\"Antalya\",\"people\":\"six\"}");

        assertEquals("$.people", violations.get(0).path());
        assertTrue(violations.get(0).message().contains("expected integer"));
    }

    @Test
    void reportsEveryProblemRatherThanTheFirst() {
        List<SchemaViolation> violations = validate(VENUE_REQUEST, "{\"people\":\"six\"}");

        assertEquals(3, violations.size(), violations.toString());
    }

    @Test
    void rejectsAValueOfTheWrongShapeEntirely() {
        assertFalse(validator.matches(json(VENUE_REQUEST), json("\"just a string\"")));
    }

    @Test
    void checksNestedObjects() {
        String schema = """
                {"type": "object", "properties": {
                   "user": {"type": "object", "properties": {"age": {"type": "integer"}},
                            "required": ["age"]}}}
                """;

        assertEquals("$.user.age", validate(schema, "{\"user\":{}}").get(0).path());
    }

    @Test
    void checksArrayElements() {
        String schema = """
                {"type": "array", "items": {"type": "object",
                 "properties": {"name": {"type": "string"}}, "required": ["name"]}}
                """;

        List<SchemaViolation> violations = validate(schema, "[{\"name\":\"ok\"},{}]");

        assertEquals("$[1].name", violations.get(0).path());
    }

    @Test
    void checksEnums() {
        String schema = "{\"type\":\"string\",\"enum\":[\"gold\",\"silver\"]}";

        assertTrue(validator.matches(json(schema), json("\"gold\"")));
        assertFalse(validator.matches(json(schema), json("\"bronze\"")));
    }

    @Test
    void treatsAnIntegerAsANumber() {
        assertTrue(validator.matches(json("{\"type\":\"number\"}"), json("6")));
    }

    @Test
    void ignoresAKeywordItDoesNotSupport() {
        String schema = "{\"type\":\"string\",\"pattern\":\"^[a-z]+$\",\"minLength\":3}";

        assertTrue(validator.matches(json(schema), json("\"ANYTHING\"")),
                "failing a valid answer because the validator is small would be worse");
    }

    @Test
    void treatsAnEmptySchemaAsNoConstraint() {
        assertTrue(validator.matches(json("{}"), json("{\"whatever\":1}")));
    }
}
