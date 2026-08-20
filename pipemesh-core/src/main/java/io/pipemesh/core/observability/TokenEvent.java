package io.pipemesh.core.observability;

import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.workflow.StepId;

import java.util.Objects;

/**
 * A piece of a model's answer, on its way to whoever is watching.
 *
 * <p>These travel the same channel as execution events on purpose. The wire
 * protocol already merges them into one stream (§26.4), so a single in-process
 * channel means the gRPC adapter is one more observer rather than a second
 * fan-out mechanism.
 *
 * <p>Unlike the other events, these are data rather than telemetry — an exporter
 * has no reason to record them, which is why the observer method does nothing by
 * default.
 */
public record TokenEvent(
        ExecutionId executionId,
        OrganizationId organization,
        StepId stepId,
        String text,
        int index) {

    public TokenEvent {
        Objects.requireNonNull(executionId, "execution id");
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(stepId, "step id");
        Objects.requireNonNull(text, "text");
    }
}
