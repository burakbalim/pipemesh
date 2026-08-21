package io.pipemesh.console.apikey;

import io.pipemesh.console.ConsoleTest;
import io.pipemesh.console.identity.IdentityRepository;
import io.pipemesh.console.web.SessionCookie;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The key screens, over HTTP, with the session doing the identifying. */
class ApiKeyEndpointTest extends ConsoleTest {

    private static final String PASSWORD = "correct horse";

    @Autowired
    private IdentityRepository accounts;

    @Autowired
    private Clock clock;

    private Cookie sessionFor(String email) throws Exception {
        http.perform(post("/api/v1/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationName": "Acme", "email": "%s", "password": "%s"}
                        """.formatted(email, PASSWORD)));

        accounts.markVerified(accounts.findUserByEmail(email).orElseThrow().id(), clock.instant());

        MvcResult signedIn = http.perform(post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, PASSWORD)))
                .andReturn();

        String header = signedIn.getResponse().getHeader("Set-Cookie");
        String value = header.substring(header.indexOf('=') + 1);
        return new Cookie(SessionCookie.NAME, value.substring(0, value.indexOf(';')));
    }

    private String issue(Cookie session, String name) throws Exception {
        return http.perform(post("/api/v1/api-keys")
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    void issuingReturnsTheSecretOnce() throws Exception {
        Cookie session = sessionFor("someone@example.com");
        issue(session, "laptop");

        http.perform(get("/api/v1/api-keys").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("laptop"))
                .andExpect(jsonPath("$[0].secret").doesNotExist());
    }

    @Test
    void withoutASessionThereAreNoKeys() throws Exception {
        http.perform(get("/api/v1/api-keys"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("not_signed_in"));
    }

    @Test
    void oneOrganizationCannotSeeAnothersKeys() throws Exception {
        Cookie mine = sessionFor("mine@example.com");
        issue(mine, "laptop");

        http.perform(get("/api/v1/api-keys").cookie(sessionFor("theirs@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void revokingSomebodyElsesKeyLooksLikeItNeverExisted() throws Exception {
        Cookie mine = sessionFor("mine@example.com");
        issue(mine, "laptop");
        String id = jdbc.queryForObject("SELECT id FROM console_api_key", String.class);

        http.perform(delete("/api/v1/api-keys/" + id).cookie(sessionFor("theirs@example.com")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api_key_unknown"));
    }

    @Test
    void revokingTwiceIsRefusedTheSecondTime() throws Exception {
        Cookie session = sessionFor("someone@example.com");
        issue(session, "laptop");
        String id = jdbc.queryForObject("SELECT id FROM console_api_key", String.class);

        http.perform(delete("/api/v1/api-keys/" + id).cookie(session)).andExpect(status().isOk());
        http.perform(delete("/api/v1/api-keys/" + id).cookie(session)).andExpect(status().isNotFound());
    }
}
