package io.pipemesh.console.billing;

import io.pipemesh.console.ConsoleTest;
import io.pipemesh.console.identity.IdentityRepository;
import io.pipemesh.console.identity.Organization;
import io.pipemesh.console.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a provider says becomes what an organization may do — once, in order, and
 * without deleting anything on the way down.
 */
class BillingTest extends ConsoleTest {

    @Autowired
    private BillingService billing;

    @Autowired
    private BillingRepository subscriptions;

    @Autowired
    private IdentityRepository accounts;

    @Autowired
    private SubscriptionService entitlement;

    @Autowired
    private Clock clock;

    private String organization() {
        Organization organization = new Organization(
                UUID.randomUUID().toString(), "Acme", "demo",
                clock.instant().minus(Duration.ofDays(1)));
        accounts.insertOrganization(organization);
        return organization.id();
    }

    private PaymentEvent event(
            String eventId, String organizationId, String planId,
            SubscriptionStatus status, long version) {

        return new PaymentEvent(eventId, new Subscription(
                organizationId, "sub_" + organizationId, planId, status,
                clock.instant().plus(Duration.ofDays(30)), version));
    }

    private String planOf(String organizationId) {
        return accounts.findOrganization(organizationId).orElseThrow().planId();
    }

    @Test
    void aPaidSubscriptionChangesWhatTheOrganizationMayDo() {
        String organizationId = organization();

        billing.apply(event("evt_1", organizationId, "unlimited", SubscriptionStatus.ACTIVE, 1));

        assertEquals("unlimited", planOf(organizationId));
    }

    @Test
    void theSameEventTwiceChangesThePlanOnce() {
        String organizationId = organization();
        PaymentEvent arrived = event("evt_1", organizationId, "unlimited",
                SubscriptionStatus.ACTIVE, 1);

        billing.apply(arrived);
        billing.apply(event("evt_2", organizationId, "demo", SubscriptionStatus.ACTIVE, 2));
        billing.apply(arrived);

        assertEquals("demo", planOf(organizationId),
                "the repeat did not put the first event's plan back");
    }

    /** Providers do not promise order, so an older event must not undo a newer one. */
    @Test
    void anOlderEventDoesNotUndoANewerOne() {
        String organizationId = organization();

        billing.apply(event("evt_2", organizationId, "unlimited", SubscriptionStatus.ACTIVE, 5));
        billing.apply(event("evt_1", organizationId, "demo", SubscriptionStatus.ACTIVE, 2));

        assertEquals("unlimited", planOf(organizationId));
        assertEquals(5, subscriptions.find(organizationId).orElseThrow().version());
    }

    @Test
    void aFailedPaymentInsideTheGracePeriodChangesNothing() {
        String organizationId = organization();
        billing.apply(event("evt_1", organizationId, "unlimited", SubscriptionStatus.ACTIVE, 1));

        billing.apply(event("evt_2", organizationId, "unlimited", SubscriptionStatus.PAST_DUE, 2));

        assertEquals("unlimited", planOf(organizationId),
                "a card that expired over a weekend is not a reason to stop somebody's work");
        assertFalse(billing.settleIfLapsed(organizationId));
    }

    @Test
    void anEndedSubscriptionFallsBackRatherThanHavingNoPlan() {
        String organizationId = organization();
        billing.apply(event("evt_1", organizationId, "unlimited", SubscriptionStatus.ACTIVE, 1));

        billing.apply(event("evt_2", organizationId, "unlimited", SubscriptionStatus.ENDED, 2));

        assertEquals("demo", planOf(organizationId),
                "an organization always has a plan, so quota never needs a special case");
    }

    /** The one that matters: narrowing a quota is not deleting a customer. */
    @Test
    void fallingBackDeletesNothing() {
        String organizationId = organization();
        jdbc.update("""
                INSERT INTO console_api_key (id, organization_id, name, key_hash, prefix)
                VALUES ('key-1', ?, 'laptop', 'hash-1', 'pm_abcd')
                """, organizationId);
        jdbc.update("""
                INSERT INTO workflow_execution
                    (execution_id, organization_id, workflow_id, workflow_version, status,
                     current_step, variables, trace_context, version)
                VALUES ('exec-1', ?, 'demo', '1.0', 'COMPLETED', 'done', '{}'::jsonb, '', 1)
                """, organizationId);

        billing.apply(event("evt_1", organizationId, "unlimited", SubscriptionStatus.ENDED, 1));

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM console_api_key WHERE organization_id = ? AND revoked_at IS NULL",
                Integer.class, organizationId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM workflow_execution WHERE organization_id = ?",
                Integer.class, organizationId));
    }

    @Test
    void anUnpaidSubscriptionFallsBackWhenTheGraceRunsOut() {
        String organizationId = organization();
        billing.apply(event("evt_1", organizationId, "unlimited", SubscriptionStatus.ACTIVE, 1));
        billing.apply(event("evt_2", organizationId, "unlimited", SubscriptionStatus.PAST_DUE, 2));

        jdbc.update("""
                UPDATE console_subscription SET current_period_end = now() - interval '30 days'
                 WHERE organization_id = ?
                """, organizationId);

        assertTrue(billing.settleIfLapsed(organizationId));
        assertEquals("demo", planOf(organizationId));
    }

    /** Entitlement is read locally; nothing here calls a provider. */
    @Test
    void aQuotaCheckNeverAsksTheProvider() {
        String organizationId = organization();
        billing.apply(event("evt_1", organizationId, "unlimited", SubscriptionStatus.ACTIVE, 1));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> entitlement.refuseIfExhausted(organizationId));
    }
}
