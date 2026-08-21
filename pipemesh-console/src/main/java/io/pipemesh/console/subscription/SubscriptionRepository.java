package io.pipemesh.console.subscription;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Plans, and what has been used against them. */
@Repository
public class SubscriptionRepository {

    private static final RowMapper<Plan> PLAN = (ResultSet rows, int index) -> new Plan(
            rows.getString("id"),
            rows.getString("name"),
            rows.getLong("max_executions"),
            rows.getLong("max_tokens"),
            rows.getLong("max_cost_micros"),
            rows.getInt("period_days"),
            List.of((String[]) rows.getArray("permissions").getArray()));

    private final JdbcTemplate jdbc;

    public SubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Plan> plans() {
        return jdbc.query("SELECT * FROM console_plan ORDER BY max_cost_micros", PLAN);
    }

    public Optional<Plan> planOf(String organizationId) {
        List<Plan> plans = jdbc.query("""
                SELECT p.* FROM console_plan p
                  JOIN console_organization o ON o.plan_id = p.id
                 WHERE o.id = ?
                """, PLAN, organizationId);

        return plans.isEmpty() ? Optional.empty() : Optional.of(plans.get(0));
    }

    public Optional<Instant> organizationCreatedAt(String organizationId) {
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT created_at FROM console_organization WHERE id = ?",
                Timestamp.class, organizationId)).map(Timestamp::toInstant);
    }

    /**
     * What one organization has spent since {@code since}.
     *
     * <p>Read straight off the executions rather than from a counter of its own.
     * The contract's own reason is the right one: {@code workflow_execution}
     * already records what every run spent, and a second copy is two numbers that
     * will disagree — after a crash between the write and the increment, after a
     * backfill, after a bug. {@code spend} is written in the same transaction as
     * the step that caused it (§39.1), which is what makes summing it trustworthy
     * rather than approximate.
     *
     * <p>No index was added for this. {@code workflow_execution_by_organization}
     * from V001 already leads with {@code organization_id}, which is the column
     * that does the narrowing.
     */
    public Usage usageSince(Instant since, Instant until, String organizationId) {
        return jdbc.queryForObject("""
                SELECT count(*)                                        AS executions,
                       coalesce(sum((spend ->> 'tokens')::bigint), 0)  AS tokens,
                       coalesce(sum((spend ->> 'costMicros')::bigint), 0) AS cost_micros
                  FROM workflow_execution
                 WHERE organization_id = ? AND created_at >= ?
                """, (ResultSet rows, int index) -> new Usage(
                        since, until,
                        rows.getLong("executions"),
                        rows.getLong("tokens"),
                        rows.getLong("cost_micros")),
                organizationId, Timestamp.from(since));
    }
}
