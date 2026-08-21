package io.pipemesh.console.demo;

import io.pipemesh.console.ConsoleTest;
import io.pipemesh.console.identity.IdentityRepository;
import io.pipemesh.console.identity.Organization;
import io.pipemesh.proto.v1.ExecutionUpdate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The demo takes the production road: an API key, the same gRPC boundary, the
 * same quota. A demo that took a short cut would prove nothing about what
 * somebody would actually buy.
 */
@Import(DemoRuntime.class)
class DemoTest extends ConsoleTest {

    @Autowired
    private DemoService demo;

    @Autowired
    private IdentityRepository accounts;

    @Autowired
    private Clock clock;

    private String organization() {
        Organization organization = new Organization(
                UUID.randomUUID().toString(), "Acme", "demo",
                clock.instant().minus(Duration.ofDays(1)));
        accounts.insertOrganization(organization);
        return organization.id();
    }

    private List<ExecutionUpdate> run(String organizationId, Map<String, String> input) {
        List<ExecutionUpdate> updates = new ArrayList<>();
        demo.run(organizationId, input, updates::add);
        return updates;
    }

    @Test
    void theDemoRunsAndFinishes() {
        List<ExecutionUpdate> updates = run(organization(), Map.of("mood", "good"));

        assertEquals(ExecutionUpdate.UpdateCase.STARTED, updates.get(0).getUpdateCase(),
                "the stream opens with where things stand");
        assertEquals(ExecutionUpdate.UpdateCase.FINISHED,
                updates.get(updates.size() - 1).getUpdateCase());
    }

    /** What #20 was for: a step says it started, not only that it ended. */
    @Test
    void theScreenSeesEachStepBegin() {
        List<String> started = run(organization(), Map.of("mood", "good")).stream()
                .filter(update -> update.getUpdateCase() == ExecutionUpdate.UpdateCase.STEP_STARTED)
                .map(update -> update.getStepStarted().getStepId())
                .toList();

        assertEquals(List.of("check", "celebrate"), started);
    }

    @Test
    void theRunBelongsToTheAccountThatAskedForIt() {
        String organizationId = organization();

        run(organizationId, Map.of("mood", "good"));

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM workflow_execution WHERE organization_id = ?",
                Integer.class, organizationId));
    }

    /**
     * The key exists for one run. Leaving it behind would quietly hand every
     * demo an extra credential nobody asked for.
     */
    @Test
    void theKeyIssuedForTheRunDoesNotOutliveIt() {
        String organizationId = organization();

        run(organizationId, Map.of("mood", "good"));

        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM console_api_key WHERE organization_id = ? AND revoked_at IS NULL",
                Integer.class, organizationId));
    }

    @Test
    void theDemoCountsAgainstThePlanLikeAnythingElse() {
        String organizationId = organization();

        run(organizationId, Map.of("mood", "good"));

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM workflow_execution WHERE organization_id = ?",
                Integer.class, organizationId),
                "no separate path means no separate accounting");
    }

    @Test
    void anAccountOverItsQuotaCannotRunTheDemoEither() {
        String organizationId = organization();
        jdbc.update("""
                INSERT INTO console_plan (id, name, max_executions, max_tokens, max_cost_micros)
                VALUES ('spent', 'Spent', 1, 0, 0)
                """);
        jdbc.update("UPDATE console_organization SET plan_id = 'spent' WHERE id = ?", organizationId);
        try {
            run(organizationId, Map.of("mood", "good"));

            Exception refused = assertThrows(RuntimeException.class,
                    () -> run(organizationId, Map.of("mood", "good")));

            assertTrue(refused.getMessage().contains("RESOURCE_EXHAUSTED"), refused.getMessage());
        } finally {
            jdbc.update("UPDATE console_organization SET plan_id = 'demo' WHERE id = ?", organizationId);
            jdbc.update("DELETE FROM console_plan WHERE id = 'spent'");
        }
    }

    @Test
    void theInputReachesTheWorkflow() {
        List<String> started = run(organization(), Map.of("mood", "bad")).stream()
                .filter(update -> update.getUpdateCase() == ExecutionUpdate.UpdateCase.STEP_STARTED)
                .map(update -> update.getStepStarted().getStepId())
                .toList();

        assertEquals(List.of("check", "console"), started,
                "the condition took the other branch, so the input arrived");
    }
}
