package io.pipemesh.core.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads the serialized form of a workflow (§8).
 *
 * <p>Structure only. Whether {@code onTrue} points at a step that exists, whether
 * anything can reach a step at all — that is {@link WorkflowCompiler}'s job, and
 * keeping the two apart means a parse error reads like a parse error.
 *
 * <p>Note what is <em>not</em> read: nothing here interprets a step's config. A
 * step type this reader has never heard of parses fine and fails later, at
 * compile time, with a message about the type rather than about JSON.
 */
public final class WorkflowDefinitionReader {

    private static final String ID = "id";
    private static final String VERSION = "version";
    private static final String ENTRY = "entry";
    private static final String STEPS = "steps";
    private static final String TYPE = "type";

    private final ObjectMapper mapper;

    public WorkflowDefinitionReader() {
        this(new ObjectMapper());
    }

    public WorkflowDefinitionReader(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public WorkflowDefinition read(String json) {
        try {
            return read(mapper.readTree(json));
        } catch (IOException malformed) {
            throw new WorkflowFormatException("workflow is not valid JSON: " + malformed.getMessage());
        }
    }

    public WorkflowDefinition read(InputStream json) {
        try {
            return read(mapper.readTree(json));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    public WorkflowDefinition read(JsonNode root) {
        Objects.requireNonNull(root, "workflow json");
        return new WorkflowDefinition(
                WorkflowId.of(required(root, ID)),
                WorkflowVersion.of(required(root, VERSION)),
                StepId.of(required(root, ENTRY)),
                steps(root));
    }

    private List<Step> steps(JsonNode root) {
        JsonNode steps = root.path(STEPS);
        if (!steps.isArray() || steps.isEmpty()) {
            throw new WorkflowFormatException("workflow must declare a non-empty '" + STEPS + "' array");
        }
        List<Step> parsed = new ArrayList<>();
        for (JsonNode step : steps) {
            parsed.add(step(step));
        }
        return parsed;
    }

    private Step step(JsonNode step) {
        return new Step(
                StepId.of(required(step, ID)),
                StepType.of(required(step, TYPE)),
                step);
    }

    private String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new WorkflowFormatException("missing required field '" + field + "'");
        }
        return value;
    }
}
