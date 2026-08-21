package io.pipemesh.console.identity;

import io.pipemesh.console.ConsoleTest;
import io.pipemesh.console.web.SessionCookie;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Getting in, staying in, and the two ways of being kept out. */
class SessionTest extends ConsoleTest {

    private static final String PASSWORD = "correct horse";

    @Autowired
    private IdentityRepository accounts;

    @Autowired
    private Clock clock;

    private String registerAndVerify(String email) throws Exception {
        http.perform(post("/api/v1/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationName": "Acme", "email": "%s", "password": "%s"}
                        """.formatted(email, PASSWORD)));

        String userId = accounts.findUserByEmail(email).orElseThrow().id();
        accounts.markVerified(userId, clock.instant());
        return userId;
    }

    private MvcResult signIn(String email, String password) throws Exception {
        return http.perform(post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, password)))
                .andReturn();
    }

    @Test
    void signingInSetsASessionCookie() throws Exception {
        registerAndVerify("someone@example.com");

        MvcResult result = signIn("someone@example.com", PASSWORD);
        String cookie = result.getResponse().getHeader("Set-Cookie");

        assertEquals(200, result.getResponse().getStatus());
        assertNotNull(cookie);
        assertTrue(cookie.contains("HttpOnly"), "a token a page can read is a token a script can take");
        assertTrue(cookie.contains("SameSite=Lax"), cookie);
    }

    @Test
    void theCookieIsWhatIdentifiesLaterRequests() throws Exception {
        registerAndVerify("someone@example.com");
        String token = tokenFrom(signIn("someone@example.com", PASSWORD));

        http.perform(get("/api/v1/session").cookie(new Cookie(SessionCookie.NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("someone@example.com"));
    }

    @Test
    void withoutACookieNobodyIsSignedIn() throws Exception {
        http.perform(get("/api/v1/session")).andExpect(status().isUnauthorized());
    }

    @Test
    void aWrongPasswordIsRefused() throws Exception {
        registerAndVerify("someone@example.com");

        http.perform(post("/api/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "someone@example.com", "password": "wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("sign_in_refused"));
    }

    @Test
    void anUnknownAddressIsRefusedIdentically() throws Exception {
        http.perform(post("/api/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "nobody@example.com", "password": "whatever"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("sign_in_refused"))
                .andExpect(jsonPath("$.message").value("email or password is incorrect"));
    }

    /** The password was right, so saying what is actually wrong reveals nothing. */
    @Test
    void anUnverifiedAccountIsToldWhatIsWrong() throws Exception {
        http.perform(post("/api/v1/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationName": "Acme", "email": "new@example.com", "password": "%s"}
                        """.formatted(PASSWORD)));

        http.perform(post("/api/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "new@example.com", "password": "%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("account_unverified"));
    }

    @Test
    void signingOutEndsTheSession() throws Exception {
        registerAndVerify("someone@example.com");
        String token = tokenFrom(signIn("someone@example.com", PASSWORD));
        Cookie cookie = new Cookie(SessionCookie.NAME, token);

        http.perform(delete("/api/v1/sessions").cookie(cookie)).andExpect(status().isOk());

        http.perform(get("/api/v1/session").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredSessionIsNoSession() throws Exception {
        String userId = registerAndVerify("someone@example.com");
        String token = tokenFrom(signIn("someone@example.com", PASSWORD));

        jdbc.update("UPDATE console_session SET expires_at = ? WHERE user_id = ?",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))), userId);

        http.perform(get("/api/v1/session").cookie(new Cookie(SessionCookie.NAME, token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theSessionTokenIsStoredOnlyAsAHash() throws Exception {
        registerAndVerify("someone@example.com");
        String token = tokenFrom(signIn("someone@example.com", PASSWORD));

        Integer matching = jdbc.queryForObject(
                "SELECT count(*) FROM console_session WHERE token_hash = ?", Integer.class, token);

        assertEquals(0, matching, "the token itself must not be a row anybody can look up");
    }

    private static String tokenFrom(MvcResult result) {
        String header = result.getResponse().getHeader("Set-Cookie");
        String value = header.substring(header.indexOf('=') + 1);
        return value.substring(0, value.indexOf(';'));
    }
}
