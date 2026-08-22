package io.pipemesh.console.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Data access for what a provider has said.
 *
 * <p>Named apart from {@code subscription.SubscriptionRepository} deliberately:
 * that one answers what a plan allows and what has been used, this one records
 * what the provider reported. Two different questions that both used to want the
 * same name.
 */
@Repository
public class BillingRepository {

    private final JdbcTemplate jdbc;

    public BillingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Subscription> find(String organizationId) {
        List<Subscription> found = jdbc.query("""
                SELECT * FROM console_subscription WHERE organization_id = ?
                """, (ResultSet rows, int index) -> new Subscription(
                        rows.getString("organization_id"),
                        rows.getString("provider_id"),
                        rows.getString("plan_id"),
                        SubscriptionStatus.valueOf(rows.getString("status")),
                        instantOf(rows.getTimestamp("current_period_end")),
                        rows.getLong("updated_version")),
                organizationId);

        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * Writes what the provider said, unless it is older than what we have.
     *
     * <p>The version comparison is in the statement rather than read first and
     * checked after: two webhooks arriving together would otherwise both see an
     * older row and both write.
     *
     * @return whether anything changed
     */
    public boolean save(Subscription subscription) {
        return jdbc.update("""
                INSERT INTO console_subscription
                    (organization_id, provider_id, plan_id, status, current_period_end,
                     updated_version, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (organization_id) DO UPDATE
                   SET provider_id = EXCLUDED.provider_id,
                       plan_id = EXCLUDED.plan_id,
                       status = EXCLUDED.status,
                       current_period_end = EXCLUDED.current_period_end,
                       updated_version = EXCLUDED.updated_version,
                       updated_at = now()
                 WHERE console_subscription.updated_version < EXCLUDED.updated_version
                """,
                subscription.organizationId(), subscription.providerId(), subscription.planId(),
                subscription.status().name(),
                subscription.currentPeriodEnd() == null
                        ? null : Timestamp.from(subscription.currentPeriodEnd()),
                subscription.version()) == 1;
    }

    /**
     * Records that an event was handled.
     *
     * @return false when it had already been seen, which is the whole point
     */
    public boolean rememberEvent(String eventId) {
        return jdbc.update("""
                INSERT INTO console_payment_event (event_id) VALUES (?)
                    ON CONFLICT (event_id) DO NOTHING
                """, eventId) == 1;
    }

    /** Entitlement lives here, and this is the only thing that moves it. */
    public void applyPlan(String organizationId, String planId) {
        jdbc.update("UPDATE console_organization SET plan_id = ? WHERE id = ?",
                planId, organizationId);
    }

    private static Instant instantOf(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
