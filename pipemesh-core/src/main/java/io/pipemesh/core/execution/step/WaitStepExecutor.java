package io.pipemesh.core.execution.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.event.EventKey;
import io.pipemesh.core.event.PendingWait;
import io.pipemesh.core.event.WaitStore;
import io.pipemesh.core.execution.ExecutionContext;
import io.pipemesh.core.execution.ResumableStepExecutor;
import io.pipemesh.core.execution.ResumeSignal;
import io.pipemesh.core.execution.StepResult;
import io.pipemesh.core.execution.SuspensionReason;
import io.pipemesh.core.expression.ExpressionException;
import io.pipemesh.core.expression.JsonPath;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Stops until something happens elsewhere (§9.7).
 *
 * <p>The waiting itself is the easy part — that machinery arrived with human
 * approval. What is new is being findable: a payment service knows an order
 * number, not which execution is listening, so the wait is filed under a key both
 * sides already know.
 *
 * <pre>
 * { "type": "wait", "event": "payment_completed", "correlationKey": "$.order.id",
 *   "output": "payment", "next": "ship", "timeoutSeconds": 3600, "onTimeout": "chase" }
 * </pre>
 *
 * <p>No thread is held, however long the wait lasts (§16).
 */
public final class WaitStepExecutor implements ResumableStepExecutor {

    private static final String EVENT = "event";
    private static final String CORRELATION_KEY = "correlationKey";
    private static final String OUTPUT = "output";
    private static final String NEXT = "next";
    private static final String TIMEOUT_SECONDS = "timeoutSeconds";
    private static final String ON_TIMEOUT = "onTimeout";

    private static final JsonNode SCHEMA = StepSchemas.parse("""
            {
              "properties": {
                "event":          {"type": "string"},
                "correlationKey": {"type": "string"},
                "output":         {"type": "string"},
                "next":           {"type": "string"},
                "timeoutSeconds": {"type": "integer"},
                "onTimeout":      {"type": "string"}
              },
              "required": ["event", "correlationKey", "next"]
            }
            """);

    private final WaitStore waits;
    private final Clock clock;

    public WaitStepExecutor(WaitStore waits) {
        this(waits, Clock.systemUTC());
    }

    public WaitStepExecutor(WaitStore waits, Clock clock) {
        this.waits = Objects.requireNonNull(waits, "wait store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The id a delivery quotes to resume this particular wait. */
    public static String waitId(ExecutionContext context, Step step) {
        return context.executionId().value() + ":" + step.id().value();
    }

    @Override
    public boolean supports(StepType type) {
        return StepType.of("wait").equals(type);
    }

    @Override
    public Optional<JsonNode> configSchema() {
        return Optional.of(SCHEMA);
    }

    @Override
    public List<StepId> outgoing(Step step) {
        return Stepwiring.stepIds(step, NEXT, ON_TIMEOUT);
    }

    @Override
    public StepResult execute(Step step, ExecutionContext context) {
        JsonNode config = step.config();

        String correlation;
        try {
            correlation = correlationOf(config, context);
        } catch (ExpressionException | IllegalArgumentException cannotBeFound) {
            // A wait filed under nothing is a wait nothing can ever find: an
            // execution stopped forever, with no error to explain it.
            return new StepResult.Failed("wait.no_correlation", cannotBeFound.getMessage(), false);
        }

        long now = clock.millis();
        EventKey key = new EventKey(
                context.organization(), config.path(EVENT).asText(""), correlation);

        waits.register(new PendingWait(
                waitId(context, step), key, context.executionId(), step.id(),
                PendingWait.Status.WAITING, now, expiryOf(config, now)));

        ObjectNode detail = JsonNodeFactory.instance.objectNode()
                .put("waitId", waitId(context, step))
                .put("event", key.name())
                .put("correlation", key.correlation());

        return new StepResult.Suspend(new SuspensionReason("event", detail), null);
    }

    @Override
    public StepResult resume(Step step, ExecutionContext context, ResumeSignal signal) {
        String expected = waitId(context, step);

        if (signal instanceof ResumeSignal.Expired expired) {
            return timedOut(step, context, expected, expired);
        }
        if (!(signal instanceof ResumeSignal.Event event)) {
            return new StepResult.Failed("wait.unexpected_signal",
                    "step " + step.id() + " waits for an event, not "
                            + signal.getClass().getSimpleName(), false);
        }
        if (!expected.equals(event.signalId())) {
            return new StepResult.Failed("wait.unknown_signal",
                    "expected wait '" + expected + "', got '" + event.signalId() + "'", false);
        }
        if (waits.settle(expected, PendingWait.Status.DELIVERED).isEmpty()) {
            return new StepResult.Failed("wait.already_settled",
                    "wait '" + expected + "' was already answered", false);
        }

        String output = step.config().path(OUTPUT).asText("");
        Map<String, JsonNode> writes = output.isBlank()
                ? Map.of()
                : Map.of(output, event.payload());

        return new StepResult.Continue(StepId.of(step.config().path(NEXT).asText()), writes);
    }

    private StepResult timedOut(
            Step step, ExecutionContext context, String expected, ResumeSignal.Expired expired) {

        if (!expected.equals(expired.signalId())) {
            return new StepResult.Failed("wait.unknown_signal",
                    "expected wait '" + expected + "', got '" + expired.signalId() + "'", false);
        }
        waits.settle(expected, PendingWait.Status.EXPIRED);

        String onTimeout = step.config().path(ON_TIMEOUT).asText("");
        if (onTimeout.isBlank()) {
            return new StepResult.Failed("wait.timed_out",
                    "nothing arrived for '" + step.config().path(EVENT).asText()
                            + "' and the step says nothing about what then", false);
        }
        return StepResult.Continue.to(StepId.of(onTimeout));
    }

    /**
     * The value both sides already know, read from the execution's variables at
     * the moment of waiting. Later changes do not move a wait that is already
     * filed.
     */
    private String correlationOf(JsonNode config, ExecutionContext context) {
        String path = config.path(CORRELATION_KEY).asText("");
        if (path.isBlank()) {
            throw new IllegalArgumentException("a wait must say what to correlate on");
        }
        JsonNode value = JsonPath.parse(path).read(context.variables());
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalArgumentException(
                    "'" + path + "' is not in this execution, so nothing could ever match this wait");
        }
        return value.asText();
    }

    private long expiryOf(JsonNode config, long now) {
        long seconds = config.path(TIMEOUT_SECONDS).asLong(0);
        return seconds > 0 ? now + seconds * 1000 : 0;
    }
}
