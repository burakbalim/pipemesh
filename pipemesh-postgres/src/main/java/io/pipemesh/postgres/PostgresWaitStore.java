package io.pipemesh.postgres;

import io.pipemesh.core.event.EventKey;
import io.pipemesh.core.event.PendingWait;
import io.pipemesh.core.event.WaitStore;
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.OrganizationId;
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
 * What executions are waiting for, on PostgreSQL.
 *
 * <p>Durable for the same reason executions are: a payment that arrives after a
 * deploy has to find the order that was waiting for it, and a wait held only in
 * memory would have been forgotten by the process that took the restart.
 */
public final class PostgresWaitStore implements WaitStore {

    private final DataSource dataSource;

    public PostgresWaitStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "data source");
    }

    @Override
    public PendingWait register(PendingWait wait) {
        String sql = """
                INSERT INTO workflow_wait
                    (wait_id, execution_id, step_id, organization_id, event_name, correlation,
                     status, waiting_since, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (wait_id) DO NOTHING
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, wait.waitId());
            statement.setString(2, wait.executionId().value());
            statement.setString(3, wait.stepId().value());
            statement.setString(4, wait.key().organization().value());
            statement.setString(5, wait.key().name());
            statement.setString(6, wait.key().correlation());
            statement.setString(7, wait.status().name());
            statement.setTimestamp(8, new Timestamp(wait.waitingSinceEpochMillis()));
            statement.setTimestamp(9, wait.neverExpires()
                    ? null : new Timestamp(wait.expiresAtEpochMillis()));
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new StateStoreException("could not register wait " + wait.waitId(), failure);
        }
        return find(wait.waitId()).orElseThrow(
                () -> new StateStoreException("wait vanished after insert", null));
    }

    @Override
    public List<PendingWait> waitingFor(EventKey key) {
        String sql = columns() + """
                 WHERE organization_id = ? AND event_name = ? AND correlation = ?
                   AND status = 'WAITING'
                 ORDER BY waiting_since
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, key.organization().value());
            statement.setString(2, key.name());
            statement.setString(3, key.correlation());
            return readAll(statement);
        } catch (SQLException failure) {
            throw new StateStoreException("could not look for waits on " + key, failure);
        }
    }

    @Override
    public Optional<PendingWait> find(String waitId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(columns() + " WHERE wait_id = ?")) {

            statement.setString(1, waitId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StateStoreException("could not read wait " + waitId, failure);
        }
    }

    @Override
    public Optional<PendingWait> settle(String waitId, PendingWait.Status outcome) {
        String sql = "UPDATE workflow_wait SET status = ? WHERE wait_id = ? AND status = 'WAITING'";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, outcome.name());
            statement.setString(2, waitId);
            if (statement.executeUpdate() != 1) {
                // Already answered: this is how the same event delivered twice
                // moves an execution once.
                return Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StateStoreException("could not settle wait " + waitId, failure);
        }
        return find(waitId);
    }

    @Override
    public List<PendingWait> expiredBy(long epochMillis, int limit) {
        String sql = columns() + """
                 WHERE status = 'WAITING' AND expires_at IS NOT NULL AND expires_at < ?
                 ORDER BY expires_at
                 LIMIT ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setTimestamp(1, new Timestamp(epochMillis));
            statement.setInt(2, Math.max(0, limit));
            return readAll(statement);
        } catch (SQLException failure) {
            throw new StateStoreException("could not scan for expired waits", failure);
        }
    }

    private String columns() {
        return """
                SELECT wait_id, execution_id, step_id, organization_id, event_name, correlation,
                       status, waiting_since, expires_at
                  FROM workflow_wait
                """;
    }

    private List<PendingWait> readAll(PreparedStatement statement) throws SQLException {
        List<PendingWait> waits = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                waits.add(read(rows));
            }
        }
        return List.copyOf(waits);
    }

    private PendingWait read(ResultSet rows) throws SQLException {
        Timestamp expires = rows.getTimestamp("expires_at");
        return new PendingWait(
                rows.getString("wait_id"),
                new EventKey(
                        OrganizationId.of(rows.getString("organization_id")),
                        rows.getString("event_name"),
                        rows.getString("correlation")),
                ExecutionId.of(rows.getString("execution_id")),
                StepId.of(rows.getString("step_id")),
                PendingWait.Status.valueOf(rows.getString("status")),
                rows.getTimestamp("waiting_since").getTime(),
                expires == null ? 0L : expires.getTime());
    }
}
