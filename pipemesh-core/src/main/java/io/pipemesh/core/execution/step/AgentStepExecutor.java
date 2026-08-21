package io.pipemesh.core.execution.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.capability.CapabilityCall;
import io.pipemesh.core.capability.CapabilityId;
import io.pipemesh.core.capability.CapabilityInvoker;
import io.pipemesh.core.capability.CapabilityResult;
import io.pipemesh.core.execution.ExecutionContext;
import io.pipemesh.core.execution.StepAttributes;
import io.pipemesh.core.execution.StepExecutor;
import io.pipemesh.core.execution.StepResult;
import io.pipemesh.core.model.CompletionRequest;
import io.pipemesh.core.model.CompletionResponse;
import io.pipemesh.core.model.MessagingProvider;
import io.pipemesh.core.model.ModelId;
import io.pipemesh.core.model.ModelRegistry;
import io.pipemesh.core.prompt.PromptId;
import io.pipemesh.core.prompt.PromptRegistry;
import io.pipemesh.core.prompt.PromptTemplate;
import io.pipemesh.core.schema.JsonSchemaValidator;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A bounded loop in which the model may call tools before answering (§9.9).
 *
 * <p>The model chooses which of the step's declared capabilities to call and when
 * to stop. The workflow chooses that this step runs at all, what it may reach,
 * when it must stop, and what happens next. That fence is the whole difference
 * between an agent step and handing an application to a model (§37).
 *
 * <p>Everything the loop reaches goes through {@link CapabilityInvoker} — the
 * same path a capability step takes. An agent with its own route to capabilities
 * would be a way around the permission check, and a boundary with a second door
 * is not one (§23).
 */
public final class AgentStepExecutor implements StepExecutor {

    private static final String MODEL = "model";
    private static final String PROMPT = "prompt";
    private static final String CAPABILITIES = "capabilities";
    private static final String MAX_ITERATIONS = "maxIterations";
    private static final String OUTPUT = "output";
    private static final String NEXT = "next";

    /**
     * What the model may answer with: one call, or the answer.
     *
     * <p>Structured output rather than a vendor's tool-calling API, so this works
     * against every provider the runtime can talk to — the same reason the model
     * boundary speaks a protocol rather than an SDK (§13, §21).
     */
    private static final String TURN_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "call": {
                  "type": "object",
                  "properties": {
                    "capability": {"type": "string"},
                    "input":      {"type": "object"}
                  },
                  "required": ["capability"]
                },
                "answer": {}
              }
            }
            """;

    private static final JsonNode SCHEMA = StepSchemas.parse("""
            {
              "properties": {
                "model":         {"type": "string"},
                "prompt":        {"type": "string"},
                "capabilities":  {"type": "array", "items": {"type": "string"}},
                "maxIterations": {"type": "integer"},
                "output":        {"type": "string"},
                "next":          {"type": "string"}
              },
              "required": ["model", "prompt", "capabilities", "maxIterations", "output", "next"]
            }
            """);

    private final ModelRegistry models;
    private final PromptRegistry prompts;
    private final CapabilityInvoker capabilities;
    private final JsonSchemaValidator validator = new JsonSchemaValidator();

    public AgentStepExecutor(
            ModelRegistry models, PromptRegistry prompts, CapabilityInvoker capabilities) {

        this.models = Objects.requireNonNull(models, "model registry");
        this.prompts = Objects.requireNonNull(prompts, "prompt registry");
        this.capabilities = Objects.requireNonNull(capabilities, "capability invoker");
    }

    @Override
    public boolean supports(StepType type) {
        return StepType.of("agent").equals(type);
    }

    @Override
    public Optional<JsonNode> configSchema() {
        return Optional.of(SCHEMA);
    }

    @Override
    public List<StepId> outgoing(Step step) {
        return Stepwiring.stepIds(step, NEXT);
    }

    @Override
    public StepResult execute(Step step, ExecutionContext context) {
        JsonNode config = step.config();
        Loop loop = new Loop(step, context, config);

        int limit = config.path(MAX_ITERATIONS).asInt(0);
        if (limit < 1) {
            return new StepResult.Failed("agent.unbounded",
                    "an agent step must say how many turns it may take", false);
        }

        for (int turn = 1; turn <= limit; turn++) {
            Optional<StepResult> settled = loop.take(turn);
            if (settled.isPresent()) {
                return settled.get();
            }
        }

        // Running out of turns is not an answer. Returning whatever the model said
        // last would present unfinished work as finished (§9.9).
        return new StepResult.Failed("agent.turns_exhausted",
                "the model did not answer within " + limit + " turns", false, loop.attributes());
    }

    /** One agent step's state: what has been tried, and what it cost. */
    private final class Loop {

        private final Step step;
        private final ExecutionContext context;
        private final JsonNode config;
        private final ArrayNode history = JsonNodeFactory.instance.arrayNode();
        private final List<String> allowed;
        private long inputTokens;
        private long outputTokens;
        private int turns;

        Loop(Step step, ExecutionContext context, JsonNode config) {
            this.step = step;
            this.context = context;
            this.config = config;
            this.allowed = new ArrayList<>();
            config.path(CAPABILITIES).forEach(capability -> allowed.add(capability.asText()));
        }

        /** @return the step's result once the model answers, or empty to go round again */
        Optional<StepResult> take(int turn) {
            turns = turn;

            ModelId model = ModelId.of(config.path(MODEL).asText(""));
            Optional<MessagingProvider> provider = models.providerFor(model);
            if (provider.isEmpty()) {
                return Optional.of(new StepResult.Failed("agent.unknown_model",
                        "no provider registered for model '" + model + "'", false, attributes()));
            }

            Optional<PromptTemplate> prompt = prompts.find(PromptId.of(config.path(PROMPT).asText("")));
            if (prompt.isEmpty()) {
                return Optional.of(new StepResult.Failed("agent.unknown_prompt",
                        "no prompt registered as '" + config.path(PROMPT).asText() + "'",
                        false, attributes()));
            }

            CompletionResponse said = provider.get().complete(new CompletionRequest(
                    model, prompt.get().render(variables()), "", turnSchema()));

            inputTokens += said.inputTokens();
            outputTokens += said.outputTokens();

            return interpret(said.content());
        }

        private Optional<StepResult> interpret(JsonNode said) {
            if (!validator.matches(turnSchema(), said)) {
                // A malformed turn costs a turn and nothing else: the loop is
                // bounded, so a model that cannot follow the shape runs out.
                record("malformed", "the answer was neither a call nor an answer");
                return Optional.empty();
            }

            if (said.has("answer")) {
                return Optional.of(new StepResult.Continue(
                        StepId.of(config.path(NEXT).asText()),
                        Map.of(config.path(OUTPUT).asText(), said.get("answer")),
                        attributes()));
            }

            if (!said.has("call")) {
                record("empty", "the model said nothing it could act on");
                return Optional.empty();
            }

            return callTool(said.path("call"));
        }

        private Optional<StepResult> callTool(JsonNode call) {
            String wanted = call.path("capability").asText("");

            if (!allowed.contains(wanted)) {
                // The fence itself. The model may choose among what the workflow
                // listed and nothing else — a capability it was not given is not
                // one it may discover (§9.9).
                record(wanted, "is not one of the capabilities this step may use");
                return Optional.empty();
            }

            CapabilityResult result = capabilities.invoke(
                    CapabilityId.of(wanted),
                    call.has("input") ? call.get("input") : JsonNodeFactory.instance.objectNode(),
                    new CapabilityCall(context.organization(), context.executionId(), step.id(),
                            context.traceParent(), context.principal()));

            switch (result) {
                case CapabilityResult.Success success -> record(wanted, success.output().toString());
                case CapabilityResult.Failure failure -> record(wanted, failure.code());
            }
            return Optional.empty();
        }

        private void record(String what, String outcome) {
            history.addObject().put("turn", turns).put("capability", what).put("result", outcome);
        }

        /**
         * What the model sees: the execution's variables, plus what this loop has
         * already tried. Without the second part every turn would repeat the first.
         */
        private ObjectNode variables() {
            ObjectNode variables = context.variables();
            variables.set("agent", JsonNodeFactory.instance.objectNode()
                    .put("turn", turns)
                    .set("history", history.deepCopy()));

            ArrayNode choices = variables.withObject("/agent").putArray("capabilities");
            allowed.forEach(choices::add);
            return variables;
        }

        Map<String, JsonNode> attributes() {
            JsonNodeFactory json = JsonNodeFactory.instance;
            Map<String, JsonNode> attributes = new HashMap<>();
            attributes.put(StepAttributes.LLM_MODEL, json.textNode(config.path(MODEL).asText("")));
            attributes.put(StepAttributes.LLM_INPUT_TOKENS, json.numberNode(inputTokens));
            attributes.put(StepAttributes.LLM_OUTPUT_TOKENS, json.numberNode(outputTokens));
            attributes.put(StepAttributes.AGENT_TURNS, json.numberNode(turns));
            attributes.put(StepAttributes.AGENT_HISTORY, history.deepCopy());
            return Map.copyOf(attributes);
        }

        private JsonNode turnSchema() {
            return StepSchemas.parse(TURN_SCHEMA);
        }
    }
}
