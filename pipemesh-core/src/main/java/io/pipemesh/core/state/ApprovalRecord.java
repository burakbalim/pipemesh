package io.pipemesh.core.state;

import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.workflow.StepId;

import java.util.Objects;

/** A pending or settled human decision (§9.4). */
public record ApprovalRecord(
        String approvalId,
        ExecutionId executionId,
        StepId stepId,
        String message,
        ApprovalStatus status,
        String decidedBy,
        String comment,
        long requestedAtEpochMillis,
        long decidedAtEpochMillis,
        long expiresAtEpochMillis) {

    public ApprovalRecord {
        Objects.requireNonNull(approvalId, "approval id");
        Objects.requireNonNull(executionId, "execution id");
        Objects.requireNonNull(stepId, "step id");
        Objects.requireNonNull(status, "status");
    }

    public enum ApprovalStatus {
        PENDING,
        APPROVED,
        REJECTED,
        EXPIRED
    }
}
