package io.pipemesh.console.billing;

import io.pipemesh.console.ConsoleTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An install with no payment configured — which is every on-premise one.
 *
 * <p>The endpoints are absent rather than refusing. An unconfigured deployment
 * answering 200 to an unsigned POST looks exactly like a working one, and that
 * is the failure worth being careful about: a 404 says there is nothing here,
 * while a 200 says there is something here that does not check.
 */
class NoProviderTest extends ConsoleTest {

    @Test
    void thereIsNoWebhookEndpointToPostTo() throws Exception {
        http.perform(post("/api/v1/webhooks/payment")
                        .contentType("application/json")
                        .content("{\"anything\": true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void thereIsNoCheckoutEither() throws Exception {
        http.perform(post("/api/v1/checkout")
                        .contentType("application/json")
                        .content("{\"planId\": \"unlimited\"}"))
                .andExpect(status().isNotFound());
    }

    /** And the rest of the console carries on: identity, keys, usage. */
    @Test
    void theConsoleStillWorksWithoutAnyOfIt() throws Exception {
        http.perform(post("/api/v1/organizations")
                        .contentType("application/json")
                        .content("""
                                {"organizationName": "Acme", "email": "on-prem@example.com",
                                 "password": "correct horse"}
                                """))
                .andExpect(status().isCreated());
    }
}
