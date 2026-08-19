package io.pipemesh.core.state;

import io.pipemesh.core.execution.ExecutionId;

import java.util.List;
import java.util.Optional;

/**
 * Pending human decisions. Separate from {@link StateStore} because it answers a
 * different question — "what is waiting for a person?" rather than "where is this
 * execution?" — and is queried by people-facing tooling that has no business
 * reading execution variables.
 */
public interface ApprovalStore {

    ApprovalRecord create(ApprovalRecord record);

    Optional<ApprovalRecord> find(String approvalId);

    List<ApprovalRecord> pendingFor(ExecutionId executionId);

    /**
     * Records a decision. Returns empty when the approval was already settled,
     * which is how a repeated resume signal is discarded rather than applied twice.
     */
    Optional<ApprovalRecord> settle(ApprovalRecord decided);
}
