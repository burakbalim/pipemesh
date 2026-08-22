package io.pipemesh.postgres;

import io.pipemesh.core.dispatch.ExecutionLease;
import io.pipemesh.core.dispatch.ExecutionLeases;
import io.pipemesh.core.execution.ExecutionId;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Leases in PostgreSQL, which is what makes dispatch work across processes.
 *
 * <p>The database is the queue. A broker would be a second answer to "what is
 * runnable" when the rows already know, and would put a message system inside
 * a library that deliberately has almost no dependencies. That trade has a
 * ceiling — this is polling, not push — and the interface is where a broker
 * would plug in when the ceiling is reached.
 */
public final class PostgresExecutionLeases implements ExecutionLeases {

    /**
     * {@code SKIP LOCKED} is the whole trick: two instances running this at the
     * same moment step over each other's locked rows instead of queueing behind
     * them, so a second instance adds throughput rather than latency.
     *
     * <p>Only {@code workflow_execution} is locked. The lease row may not exist
     * yet, and the nullable side of an outer join cannot be locked anyway.
     */
    private static final String CLAIM = """
            WITH claimable AS (
                SELECT e.execution_id
                  FROM workflow_execution e
                  LEFT JOIN workflow_lease l ON l.execution_id = e.execution_id
                 WHERE e.status IN ('CREATED', 'RUNNING')
                   AND (l.execution_id IS NULL OR l.expires_at <= ?)
                 ORDER BY e.updated_at
                 LIMIT ?
                   FOR UPDATE OF e SKIP LOCKED
            )
            INSERT INTO workflow_lease (execution_id, owner, token, expires_at)
            SELECT execution_id, ?, ?, ? FROM claimable
                ON CONFLICT (execution_id) DO UPDATE
               SET owner = EXCLUDED.owner,
                   token = EXCLUDED.token,
                   expires_at = EXCLUDED.expires_at
            RETURNING execution_id, owner, token, expires_at
            """;

    private static final String RENEW = """
            UPDATE workflow_lease SET expires_at = ?
             WHERE execution_id = ? AND owner = ? AND token = ?
            """;

    private static final String RELEASE = """
            DELETE FROM workflow_lease
             WHERE execution_id = ? AND owner = ? AND token = ?
            """;

    /**
     * The same predicate {@code CLAIM} uses, asked rather than acted on.
     *
     * <p>{@code WAITING} is not in the drivable set and that is what keeps this
     * metric honest: an execution that has been waiting three days for an
     * approval is a waiting person, not a queue, and counting it would have an
     * autoscaler adding drivers forever.
     */
    private static final String BACKLOG = """
            SELECT count(*)                                                  AS waiting,
                   coalesce(max(extract(epoch from (now() - e.updated_at))), 0) AS oldest_seconds
              FROM workflow_execution e
              LEFT JOIN workflow_lease l ON l.execution_id = e.execution_id
             WHERE e.status IN ('CREATED', 'RUNNING')
               AND (l.execution_id IS NULL OR l.expires_at <= ?)
            """;

    private final DataSource dataSource;
    private final Clock clock;

    public PostgresExecutionLeases(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public PostgresExecutionLeases(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "data source");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<ExecutionLease> claim(String owner, Duration duration, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        long now = clock.millis();
        String token = UUID.randomUUID().toString();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(CLAIM)) {

            statement.setLong(1, now);
            statement.setInt(2, limit);
            statement.setString(3, owner);
            statement.setString(4, token);
            statement.setLong(5, now + duration.toMillis());

            return read(statement);
        } catch (SQLException failure) {
            throw new IllegalStateException("could not claim executions for " + owner, failure);
        }
    }

    @Override
    public Optional<ExecutionLease> renew(ExecutionLease lease, Duration duration) {
        long expiresAt = clock.millis() + duration.toMillis();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(RENEW)) {

            statement.setLong(1, expiresAt);
            statement.setString(2, lease.executionId().value());
            statement.setString(3, lease.owner());
            statement.setString(4, lease.token());

            return statement.executeUpdate() == 1
                    ? Optional.of(new ExecutionLease(
                            lease.executionId(), lease.owner(), lease.token(), expiresAt))
                    : Optional.empty();
        } catch (SQLException failure) {
            throw new IllegalStateException("could not renew " + lease.executionId(), failure);
        }
    }

    /**
     * Scoped to owner and token so a driver whose lease was already taken over
     * cannot delete the new owner's claim on its way out.
     */
    @Override
    public void release(ExecutionLease lease) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(RELEASE)) {

            statement.setString(1, lease.executionId().value());
            statement.setString(2, lease.owner());
            statement.setString(3, lease.token());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("could not release " + lease.executionId(), failure);
        }
    }

    @Override
    public Backlog backlog() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(BACKLOG)) {

            statement.setLong(1, clock.millis());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return new Backlog(
                        rows.getLong("waiting"),
                        (long) (rows.getDouble("oldest_seconds") * 1_000));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("could not measure the backlog", failure);
        }
    }

    private List<ExecutionLease> read(PreparedStatement statement) throws SQLException {
        List<ExecutionLease> claimed = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                claimed.add(new ExecutionLease(
                        ExecutionId.of(rows.getString("execution_id")),
                        rows.getString("owner"),
                        rows.getString("token"),
                        rows.getLong("expires_at")));
            }
        }
        return claimed;
    }
}
