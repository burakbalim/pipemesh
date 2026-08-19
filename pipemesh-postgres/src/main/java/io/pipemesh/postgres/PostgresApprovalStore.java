package io.pipemesh.postgres;

import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.state.ApprovalRecord;
import io.pipemesh.core.state.ApprovalStore;
import io.pipemesh.core.workflow.StepId;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pending human decisions on PostgreSQL.
 *
 * <p>Two statements here carry the idempotency guarantees. The insert does
 * nothing on conflict, so an execution that requests the same approval twice
 * lands on the same row. The update only touches a row that is still
 * {@code PENDING}, so a decision that arrives twice settles once and the second
 * caller learns it changed nothing.
 */
public final class PostgresApprovalStore implements ApprovalStore {

    private final DataSource dataSource;

    public PostgresApprovalStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "data source");
    }

    @Override
    public ApprovalRecord create(ApprovalRecord record) {
        String sql = """
                INSERT INTO workflow_approval
                    (approval_id, execution_id, step_id, message, status, requested_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (approval_id) DO NOTHING
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, record.approvalId());
            statement.setString(2, record.executionId().value());
            statement.setString(3, record.stepId().value());
            statement.setString(4, record.message());
            statement.setString(5, record.status().name());
            statement.setTimestamp(6, new Timestamp(record.requestedAtEpochMillis()));
            statement.setTimestamp(7, timestampOrNull(record.expiresAtEpochMillis()));
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new StateStoreException("could not create approval " + record.approvalId(), failure);
        }
        return find(record.approvalId()).orElseThrow(
                () -> new StateStoreException("approval vanished after insert", null));
    }

    @Override
    public Optional<ApprovalRecord> find(String approvalId) {
        String sql = baseQuery() + " WHERE approval_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, approvalId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StateStoreException("could not read approval " + approvalId, failure);
        }
    }

    @Override
    public List<ApprovalRecord> pendingFor(ExecutionId executionId) {
        String sql = baseQuery() + " WHERE execution_id = ? AND status = 'PENDING' ORDER BY requested_at";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, executionId.value());
            List<ApprovalRecord> pending = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    pending.add(read(rows));
                }
            }
            return List.copyOf(pending);
        } catch (SQLException failure) {
            throw new StateStoreException("could not read approvals of " + executionId, failure);
        }
    }

    @Override
    public Optional<ApprovalRecord> settle(ApprovalRecord decided) {
        String sql = """
                UPDATE workflow_approval
                   SET status = ?, decided_by = ?, comment = ?, decided_at = ?
                 WHERE approval_id = ? AND status = 'PENDING'
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, decided.status().name());
            statement.setString(2, decided.decidedBy());
            statement.setString(3, decided.comment());
            statement.setTimestamp(4, new Timestamp(decided.decidedAtEpochMillis()));
            statement.setString(5, decided.approvalId());

            if (statement.executeUpdate() != 1) {
                return Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StateStoreException("could not settle approval " + decided.approvalId(), failure);
        }
        return find(decided.approvalId());
    }

    private String baseQuery() {
        return """
                SELECT approval_id, execution_id, step_id, message, status, decided_by, comment,
                       requested_at, decided_at, expires_at
                  FROM workflow_approval
                """;
    }

    private ApprovalRecord read(ResultSet rows) throws SQLException {
        return new ApprovalRecord(
                rows.getString("approval_id"),
                ExecutionId.of(rows.getString("execution_id")),
                StepId.of(rows.getString("step_id")),
                rows.getString("message"),
                ApprovalRecord.ApprovalStatus.valueOf(rows.getString("status")),
                rows.getString("decided_by"),
                rows.getString("comment"),
                rows.getTimestamp("requested_at").getTime(),
                millisOrZero(rows.getTimestamp("decided_at")),
                millisOrZero(rows.getTimestamp("expires_at")));
    }

    private long millisOrZero(Timestamp timestamp) {
        return timestamp == null ? 0L : timestamp.getTime();
    }

    private Timestamp timestampOrNull(long epochMillis) {
        return epochMillis == 0L ? null : new Timestamp(epochMillis);
    }
}
