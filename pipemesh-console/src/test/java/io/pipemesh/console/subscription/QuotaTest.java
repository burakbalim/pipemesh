package io.pipemesh.console.subscription;

import io.pipemesh.console.ConsoleTest;
import io.pipemesh.console.identity.IdentityRepository;
import io.pipemesh.console.identity.Organization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A plan is a gate in front of the runtime. The engine knows nothing about it
 * and must not (§3).
 */
class QuotaTest extends ConsoleTest {

    @Autowired
    private SubscriptionService subscriptions;

    @Autowired
    private IdentityRepository accounts;

    @Autowired
    private Clock clock;

    /**
     * Created ten days ago, so an execution "an hour ago" falls inside the
     * current period rather than before the account existed.
     */
    private String organization() {
        Organization organization = new Organization(
                UUID.randomUUID().toString(), "Acme", "demo",
                clock.instant().minus(Duration.ofDays(10)));
        accounts.insertOrganization(organization);
        return organization.id();
    }

    /** Writes the row the runtime would have written for a finished execution. */
    private void executionCosting(String organizationId, long tokens, long costMicros, Duration ago) {
        jdbc.update("""
                INSERT INTO workflow_execution
                    (execution_id, organization_id, workflow_id, workflow_version, status,
                     current_step, variables, trace_context, version, created_at, updated_at, spend)
                VALUES (?, ?, 'demo', '1.0', 'COMPLETED', 'done', '{}'::jsonb, '', 1, ?, ?, ?::jsonb)
                """,
                UUID.randomUUID().toString(), organizationId,
                Timestamp.from(clock.instant().minus(ago)),
                Timestamp.from(clock.instant().minus(ago)),
                """
                {"modelCalls":1,"unpricedCalls":0,"tokens":%d,"costMicros":%d}
                """.formatted(tokens, costMicros));
    }

    @Test
    void afreshOrganizationHasUsedNothing() {
        Usage usage = subscriptions.usageOf(organization());

        assertEquals(0, usage.executions());
        assertEquals(0, usage.costMicros());
    }

    @Test
    void usageIsReadOffTheExecutionsThemselves() {
        String organizationId = organization();
        executionCosting(organizationId, 1000, 11_250, Duration.ZERO);
        executionCosting(organizationId, 2000, 22_500, Duration.ofHours(1));

        Usage usage = subscriptions.usageOf(organizationId);

        assertEquals(2, usage.executions());
        assertEquals(3000, usage.tokens());
        assertEquals(33_750, usage.costMicros());
    }

    @Test
    void oneOrganizationsUsageIsNotAnothers() {
        String mine = organization();
        executionCosting(organization(), 5000, 99_999, Duration.ZERO);

        assertEquals(0, subscriptions.usageOf(mine).executions());
    }

    @Test
    void workFromAPreviousPeriodDoesNotCount() {
        String organizationId = organization();
        executionCosting(organizationId, 1000, 11_250, Duration.ofDays(45));

        assertEquals(0, subscriptions.usageOf(organizationId).executions(),
                "the demo plan's period is 30 days");
    }

    @Test
    void anOrganizationInsideItsPlanIsNotRefused() {
        String organizationId = organization();
        executionCosting(organizationId, 100, 100, Duration.ZERO);

        assertDoesNotThrow(() -> subscriptions.refuseIfExhausted(organizationId));
    }

    @Test
    void spendingThePlansMoneyStopsTheNextExecution() {
        String organizationId = organization();
        executionCosting(organizationId, 10, 500_000, Duration.ZERO);

        Exception refused = assertThrows(QuotaExceededException.class,
                () -> subscriptions.refuseIfExhausted(organizationId));

        assertEquals("this plan's spend for the period is used up", refused.getMessage());
    }

    @Test
    void runningTheLastAllowedExecutionStopsTheOneAfterIt() {
        String organizationId = organization();
        for (int run = 0; run < 50; run++) {
            executionCosting(organizationId, 1, 1, Duration.ZERO);
        }

        Exception refused = assertThrows(QuotaExceededException.class,
                () -> subscriptions.refuseIfExhausted(organizationId));

        assertEquals("this plan allows 50 executions per period", refused.getMessage(),
                "fifty of fifty means the next one is the fifty-first");
    }

    /** The unlimited plan ships in the schema; this no longer invents one. */
    @Test
    void aPlanWithNoLimitRefusesNothing() {
        String organizationId = organization();
        jdbc.update("UPDATE console_organization SET plan_id = 'unlimited' WHERE id = ?",
                organizationId);
        for (int run = 0; run < 100; run++) {
            executionCosting(organizationId, 10_000, 10_000_000, Duration.ZERO);
        }

        assertDoesNotThrow(() -> subscriptions.refuseIfExhausted(organizationId));
    }
}
