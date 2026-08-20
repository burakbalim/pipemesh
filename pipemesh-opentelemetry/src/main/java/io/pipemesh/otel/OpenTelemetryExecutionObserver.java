package io.pipemesh.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import com.fasterxml.jackson.databind.JsonNode;
import io.pipemesh.core.execution.StepAttributes;
import io.pipemesh.core.observability.ExecutionEvent;
import io.pipemesh.core.observability.ExecutionObserver;
import io.pipemesh.core.observability.StepEvent;
import io.pipemesh.core.observability.TraceContext;
import io.pipemesh.core.state.StepRecord;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Publishes execution telemetry as OpenTelemetry spans and metrics.
 *
 * <p>With this in place, Datadog, New Relic, Grafana and Honeycomb are a matter of
 * configuration rather than code: they all ingest OTLP, so pointing an exporter
 * at one of them is the whole integration (§22.1).
 *
 * <p><b>An execution is a trace, not a span.</b> A workflow that waits three days
 * for an approval outlives the process that started it, so there is no live span
 * to keep open — and pretending otherwise is how durable workflows end up as two
 * unrelated traces. Each step becomes a span parented to the execution's stored
 * trace context, which is why the same execution reads as one trace whichever
 * process ran which part of it (§22.3).
 *
 * <p>Spans are recorded after the fact with explicit timestamps, so a step that
 * took four seconds shows as four seconds even though the span object was created
 * once it had already finished.
 */
public final class OpenTelemetryExecutionObserver implements ExecutionObserver {

    private static final String SCOPE = "io.pipemesh";

    private final Tracer tracer;
    private final LongCounter executions;
    private final DoubleHistogram executionDuration;
    private final DoubleHistogram stepDuration;
    private final DoubleHistogram approvalWait;
    private final LongCounter recoveries;
    private final LongCounter attempts;
    private final LongCounter inputTokens;
    private final LongCounter outputTokens;

    public OpenTelemetryExecutionObserver(OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "opentelemetry");
        this.tracer = openTelemetry.getTracer(SCOPE);

        Meter meter = openTelemetry.getMeter(SCOPE);
        this.executions = meter.counterBuilder("pipemesh.workflow.executions")
                .setDescription("Executions that reached a terminal status, by status")
                .build();
        this.executionDuration = meter.histogramBuilder("pipemesh.workflow.duration")
                .setDescription("Wall-clock time from start to terminal status, waits included")
                .setUnit("ms")
                .build();
        this.stepDuration = meter.histogramBuilder("pipemesh.step.duration")
                .setDescription("Time one step took")
                .setUnit("ms")
                .build();
        this.approvalWait = meter.histogramBuilder("pipemesh.approval.wait_time")
                .setDescription("How long an execution sat waiting before a decision arrived")
                .setUnit("ms")
                .build();
        this.recoveries = meter.counterBuilder("pipemesh.workflow.recoveries")
                .setDescription("Executions picked up after the process running them died")
                .build();
        this.attempts = meter.counterBuilder("pipemesh.step.attempts")
                .setDescription("Step attempts, by outcome — a retry that worked is not a success first time")
                .build();
        this.inputTokens = meter.counterBuilder("pipemesh.llm.input_tokens")
                .setDescription("Prompt tokens spent")
                .build();
        this.outputTokens = meter.counterBuilder("pipemesh.llm.output_tokens")
                .setDescription("Completion tokens spent")
                .build();
    }

    @Override
    public void stepFinished(StepEvent event) {
        long endedAt = event.execution().atEpochMillis();
        long startedAt = endedAt - event.latencyMillis();

        Attributes attributes = attributesOf(event.attributes());

        Span span = tracer.spanBuilder(spanName(event))
                .setParent(parentOf(event.execution()))
                .setSpanKind(SpanKind.INTERNAL)
                .setStartTimestamp(Instant.ofEpochMilli(startedAt))
                .setAllAttributes(attributes)
                .startSpan();

        if (event.outcome() == StepRecord.StepOutcome.FAILED) {
            span.setStatus(StatusCode.ERROR);
        }
        span.end(Instant.ofEpochMilli(endedAt));

        stepDuration.record(event.latencyMillis(), attributes);
        attempts.add(1, attributes);
        countTokens(event, attributes);
    }

    @Override
    public void executionRecovered(ExecutionEvent event) {
        // Worth counting on its own: a rising number here means processes are
        // dying mid-step, which is a different problem from workflows failing.
        recoveries.add(1, attributesOf(event.attributes()));
    }

    @Override
    public void executionResumed(ExecutionEvent event) {
        // The gap since the last write is the wait, and it is measured from the
        // stored record — so a decision that took three days is reported even
        // though no process was watching for most of it.
        approvalWait.record(event.sinceLastWriteMillis(), attributesOf(event.attributes()));
    }

    @Override
    public void executionFinished(ExecutionEvent event) {
        Attributes attributes = attributesOf(event.attributes());
        executions.add(1, attributes);
        executionDuration.record(event.ageMillis(), attributes);
    }

    private void countTokens(StepEvent event, Attributes attributes) {
        tokenCount(event, StepAttributes.LLM_INPUT_TOKENS)
                .ifPresent(count -> inputTokens.add(count, attributes));
        tokenCount(event, StepAttributes.LLM_OUTPUT_TOKENS)
                .ifPresent(count -> outputTokens.add(count, attributes));
    }

    private Optional<Long> tokenCount(StepEvent event, String name) {
        return Optional.ofNullable(event.reported().get(name))
                .filter(JsonNode::canConvertToLong)
                .map(JsonNode::asLong);
    }

    private String spanName(StepEvent event) {
        return event.stepType().name() + " " + event.stepId().value();
    }

    /**
     * Rebuilds the execution's trace context as a remote parent. The parent span
     * itself may have ended in another process, or on another machine — all that
     * is needed here is its identity.
     */
    private Context parentOf(ExecutionEvent event) {
        TraceContext trace = event.traceIfAny().orElse(null);
        if (trace == null) {
            return Context.root();
        }
        SpanContext parent = SpanContext.createFromRemoteParent(
                trace.traceId(),
                trace.spanId(),
                trace.sampled() ? TraceFlags.getSampled() : TraceFlags.getDefault(),
                TraceState.getDefault());

        return parent.isValid() ? Context.root().with(Span.wrap(parent)) : Context.root();
    }

    private Attributes attributesOf(Map<String, String> attributes) {
        AttributesBuilder builder = Attributes.builder();
        attributes.forEach((name, value) -> builder.put(AttributeKey.stringKey(name), value));
        return builder.build();
    }
}
