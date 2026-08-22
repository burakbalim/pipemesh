package io.pipemesh.console.identity;

import io.pipemesh.console.ConsoleTest;
import io.pipemesh.console.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An on-premise install pays by contract, so a new account there must not land
 * on the plan meant for strangers.
 */
@TestPropertySource(properties = "console.defaultPlan=unlimited")
class DefaultPlanTest extends ConsoleTest {

    @Autowired
    private RegistrationService registrations;

    @Autowired
    private IdentityRepository accounts;

    @Autowired
    private SubscriptionService subscriptions;

    @Autowired
    private Clock clock;

    private String register(String email) {
        registrations.register("Acme", email, "correct horse");
        return accounts.findUserByEmail(email).orElseThrow().organizationId();
    }

    @Test
    void anAccountLandsOnTheConfiguredPlan() {
        String organizationId = register("someone@example.com");

        assertEquals("unlimited",
                accounts.findOrganization(organizationId).orElseThrow().planId());
    }

    /**
     * The quota code still runs; it simply finds nothing to refuse. Skipping the
     * check with a branch would make the two paths diverge, and only one of them
     * would stay tested.
     */
    @Test
    void anUnlimitedPlanRefusesNothingHoweverMuchIsUsed() {
        String organizationId = register("someone@example.com");
        for (int run = 0; run < 200; run++) {
            jdbc.update("""
                    INSERT INTO workflow_execution
                        (execution_id, organization_id, workflow_id, workflow_version, status,
                         current_step, variables, trace_context, version, created_at, updated_at, spend)
                    VALUES (?, ?, 'demo', '1.0', 'COMPLETED', 'done', '{}'::jsonb, '', 1, ?, ?, ?::jsonb)
                    """,
                    UUID.randomUUID().toString(), organizationId,
                    Timestamp.from(clock.instant()), Timestamp.from(clock.instant()),
                    "{\"modelCalls\":1,\"unpricedCalls\":0,\"tokens\":99999,\"costMicros\":9999999}");
        }

        assertDoesNotThrow(() -> subscriptions.refuseIfExhausted(organizationId));
    }

    @Test
    void theUnlimitedPlanIsARowRatherThanABranch() {
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM console_plan WHERE id = 'unlimited'", Integer.class));
    }
}
