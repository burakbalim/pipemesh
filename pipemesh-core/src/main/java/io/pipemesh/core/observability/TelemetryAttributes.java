package io.pipemesh.core.observability;

/**
 * Attribute names every observer should agree on.
 *
 * <p>Shared names are what let two backends show the same thing: a dashboard
 * grouping by {@code pipemesh.organization} works whether the data reached
 * Datadog, New Relic or a log file.
 */
public final class TelemetryAttributes {

    public static final String ORGANIZATION = "pipemesh.organization";
    public static final String WORKFLOW_ID = "pipemesh.workflow.id";
    public static final String WORKFLOW_VERSION = "pipemesh.workflow.version";
    public static final String EXECUTION_ID = "pipemesh.execution.id";
    public static final String EXECUTION_STATUS = "pipemesh.execution.status";
    public static final String STEP_ID = "pipemesh.step.id";
    public static final String STEP_TYPE = "pipemesh.step.type";
    public static final String STEP_OUTCOME = "pipemesh.step.outcome";
    public static final String STEP_ATTEMPT = "pipemesh.step.attempt";
    public static final String RECOVERY_REPEATED = "pipemesh.recovery.repeated";

    private TelemetryAttributes() {
    }
}
