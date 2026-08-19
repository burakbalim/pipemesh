package io.pipemesh.core.state.memory;

import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.state.ApprovalRecord;
import io.pipemesh.core.state.ApprovalStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** An {@link ApprovalStore} that lives only as long as the process. */
public final class InMemoryApprovalStore implements ApprovalStore {

    private final Map<String, ApprovalRecord> approvals = new ConcurrentHashMap<>();

    @Override
    public ApprovalRecord create(ApprovalRecord record) {
        ApprovalRecord existing = approvals.putIfAbsent(record.approvalId(), record);
        return existing == null ? record : existing;
    }

    @Override
    public Optional<ApprovalRecord> find(String approvalId) {
        return Optional.ofNullable(approvals.get(approvalId));
    }

    @Override
    public List<ApprovalRecord> pendingFor(ExecutionId executionId) {
        return approvals.values().stream()
                .filter(approval -> approval.executionId().equals(executionId))
                .filter(approval -> approval.status() == ApprovalRecord.ApprovalStatus.PENDING)
                .toList();
    }

    @Override
    public Optional<ApprovalRecord> settle(ApprovalRecord decided) {
        ApprovalRecord current = approvals.get(decided.approvalId());
        if (current == null || current.status() != ApprovalRecord.ApprovalStatus.PENDING) {
            return Optional.empty();
        }
        boolean replaced = approvals.replace(decided.approvalId(), current, decided);
        return replaced ? Optional.of(decided) : Optional.empty();
    }
}
