package io.pipemesh.otel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.ExecutionHandle;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.execution.ResumeSignal;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.WorkflowRuntime;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.ConditionStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.observability.TelemetryAttributes;
import io.pipemesh.core.state.memory.InMemoryApprovalStore;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against the real OpenTelemetry SDK with in-memory exporters, so what is
 * asserted is the spans and metrics a backend would actually receive.
 */
class OpenTelemetryExecutionObserverTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BOOKING = """
            {
              "id": "venue_booking", "version": "1.0", "entry": "check_price",
              "steps": [
                {"id": "check_price", "type": "condition", "expression": "$.input.price > 100",
                 "onTrue": "approval", "onFalse": "booked"},
                {"id": "approval", "type": "human_approval", "message": "Book?",
                 "onApproved": "booked", "onRejected": "cancelled"},
                {"id": "booked", "type": "terminal", "status": "COMPLETED"},
                {"id": "cancelled", "type": "terminal", "status": "CANCELLED"}
              ]
            }
            """;

    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final InMemoryMetricReader metrics = InMemoryMetricReader.create();
    private final InMemoryStateStore stateStore = new InMemoryStateStore();
    private final InMemoryApprovalStore approvals = new InMemoryApprovalStore();

    private WorkflowRuntime runtime;

    @BeforeEach
    void wire() {
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(spans))
                        .build())
                .setMeterProvider(SdkMeterProvider.builder()
                        .registerMetricReader(metrics)
                        .build())
                .build();

        StepExecutors executors = StepExecutors.of(
                new ConditionStepExecutor(),
                new ApprovalStepExecutor(approvals),
                new TerminalStepExecutor());

        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        workflows.register(new WorkflowDefinitionReader().read(BOOKING));

        runtime = new DefaultWorkflowRuntime(workflows, stateStore, new WorkflowExecutor(
                stateStore, executors, new OpenTelemetryExecutionObserver(sdk)));
    }

    private ExecutionInput input(String json) {
        try {
            return new ExecutionInput((ObjectNode) JSON.readTree(json));
        } catch (Exception malformed) {
            throw new IllegalArgumentException(malformed);
        }
    }

    private ExecutionHandle startExpensive(OrganizationId organization) {
        return runtime.start(ExecutionRequest.of(
                WorkflowId.of("venue_booking"), input("{\"price\":250}"), organization));
    }

    private ExecutionHandle approve(ExecutionHandle waiting) {
        return runtime.resume(waiting.executionId(), new ResumeSignal.Approval(
                waiting.executionId().value() + ":approval", true, "burak", ""));
    }

    private List<MetricData> metricNamed(String name) {
        return metrics.collectAllMetrics().stream()
                .filter(metric -> metric.getName().equals(name))
                .toList();
    }

    @Test
    void exportsASpanForEveryStep() {
        approve(startExpensive(OrganizationId.DEFAULT));

        List<String> names = spans.getFinishedSpanItems().stream().map(SpanData::getName).toList();

        assertEquals(
                List.of("condition check_price", "human_approval approval",
                        "human_approval approval", "terminal booked"),
                names);
    }

    @Test
    void putsEveryStepOfOneExecutionInOneTrace() {
        approve(startExpensive(OrganizationId.DEFAULT));

        Set<String> traceIds = spans.getFinishedSpanItems().stream()
                .map(SpanData::getTraceId)
                .collect(Collectors.toSet());

        assertEquals(1, traceIds.size(),
                "a workflow that waited and resumed must still read as one trace");
    }

    @Test
    void labelsSpansWithTheOrganization() {
        approve(startExpensive(OrganizationId.of("acme")));

        SpanData first = spans.getFinishedSpanItems().get(0);

        assertEquals("acme", first.getAttributes().asMap().entrySet().stream()
                .filter(entry -> entry.getKey().getKey().equals(TelemetryAttributes.ORGANIZATION))
                .map(entry -> String.valueOf(entry.getValue()))
                .findFirst()
                .orElseThrow());
    }

    @Test
    void recordsSpanTimingFromWhenTheStepActuallyRan() {
        approve(startExpensive(OrganizationId.DEFAULT));

        SpanData first = spans.getFinishedSpanItems().get(0);

        assertTrue(first.getEndEpochNanos() >= first.getStartEpochNanos());
    }

    @Test
    void countsExecutionsThatReachedATerminalStatus() {
        approve(startExpensive(OrganizationId.DEFAULT));

        assertEquals(1, metricNamed("pipemesh.workflow.executions").size());
    }

    @Test
    void recordsHowLongTheWholeExecutionTook() {
        approve(startExpensive(OrganizationId.DEFAULT));

        assertEquals(1, metricNamed("pipemesh.workflow.duration").size());
    }

    @Test
    void recordsHowLongTheApprovalWasWaitedFor() {
        approve(startExpensive(OrganizationId.DEFAULT));

        assertEquals(1, metricNamed("pipemesh.approval.wait_time").size());
    }

    @Test
    void recordsEveryStepsDuration() {
        approve(startExpensive(OrganizationId.DEFAULT));

        assertEquals(1, metricNamed("pipemesh.step.duration").size());
    }

    @Test
    void publishesNothingForAnExecutionThatHasNotFinished() {
        startExpensive(OrganizationId.DEFAULT);

        assertEquals(0, metricNamed("pipemesh.workflow.executions").size());
        assertEquals(2, spans.getFinishedSpanItems().size(), "condition and the suspended approval");
    }
}
