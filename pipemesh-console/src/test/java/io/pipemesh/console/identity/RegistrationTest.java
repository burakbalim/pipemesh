package io.pipemesh.console.identity;

import io.pipemesh.console.ConsoleTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Opening an account, and the three ways it is allowed to go wrong. */
class RegistrationTest extends ConsoleTest {

    @Autowired
    private RegistrationService registrations;

    @Autowired
    private IdentityRepository accounts;

    @Autowired
    private Clock clock;

    private String register(String email) throws Exception {
        http.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organizationName": "Acme", "email": "%s", "password": "correct horse"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planId").value("demo"));

        return accounts.findUserByEmail(email).orElseThrow().id();
    }

    /** The link is only ever in the mail; the test reads it from where it was filed. */
    private String linkFor(String userId) {
        return jdbc.queryForObject(
                "SELECT token_hash FROM console_verification WHERE user_id = ?", String.class, userId);
    }

    @Test
    void registeringCreatesAnOrganizationOnTheDemoPlan() throws Exception {
        String userId = register("someone@example.com");

        assertEquals("demo", accounts.findUser(userId)
                .flatMap(user -> accounts.findOrganization(user.organizationId()))
                .orElseThrow().planId());
    }

    @Test
    void theSameAddressCannotBeRegisteredTwice() throws Exception {
        register("someone@example.com");

        http.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organizationName": "Other", "email": "someone@example.com",
                                 "password": "another one"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("email_already_registered"));
    }

    @Test
    void addressesAreOwnedRegardlessOfCapitals() throws Exception {
        register("someone@example.com");

        http.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organizationName": "Other", "email": "SomeOne@Example.com",
                                 "password": "another one"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void thePasswordIsNotStoredAnywhereItCouldBeRead() throws Exception {
        String userId = register("someone@example.com");

        String stored = accounts.findUser(userId).orElseThrow().passwordHash();

        assertNotEquals("correct horse", stored);
        assertTrue(stored.startsWith("$argon2"), stored);
    }

    @Test
    void averificationLinkIsStoredOnlyAsAHash() throws Exception {
        String userId = register("someone@example.com");

        List<String> tokens = jdbc.queryForList(
                "SELECT token_hash FROM console_verification WHERE user_id = ?",
                String.class, userId);

        assertEquals(1, tokens.size());
        assertEquals(64, tokens.get(0).length(), "a SHA-256 hex digest, not the token");
    }

    @Test
    void verifyingMarksTheAccount() throws Exception {
        String userId = register("someone@example.com");
        String token = plainTokenFor(userId);

        registrations.verify(token);

        assertTrue(accounts.findUser(userId).orElseThrow().verified());
    }

    @Test
    void aLinkCannotBeUsedTwice() throws Exception {
        String userId = register("someone@example.com");
        String token = plainTokenFor(userId);
        registrations.verify(token);

        Exception refused = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidVerificationException.class, () -> registrations.verify(token));

        assertTrue(refused.getMessage().contains("already been used"), refused.getMessage());
    }

    @Test
    void anExpiredLinkIsRefused() throws Exception {
        String userId = register("someone@example.com");
        String token = plainTokenFor(userId);

        jdbc.update("UPDATE console_verification SET expires_at = ? WHERE user_id = ?",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))), userId);

        org.junit.jupiter.api.Assertions.assertThrows(
                InvalidVerificationException.class, () -> registrations.verify(token));
    }

    @Test
    void anUnknownLinkIsRefusedTheSameWay() {
        org.junit.jupiter.api.Assertions.assertThrows(
                InvalidVerificationException.class, () -> registrations.verify("not-a-token"));
    }

    /**
     * Only the hash is stored, so a test cannot read the token back. It files a
     * known one instead — which is also how it proves the hash is what is checked.
     */
    private String plainTokenFor(String userId) {
        String token = Tokens.generate();
        jdbc.update("DELETE FROM console_verification WHERE user_id = ?", userId);
        accounts.insertVerification(
                Tokens.hash(token), userId, clock.instant().plus(Duration.ofHours(1)));
        return token;
    }
}
