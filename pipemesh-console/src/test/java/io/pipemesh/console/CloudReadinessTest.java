package io.pipemesh.console;

import io.pipemesh.console.identity.LoggingVerificationLinkSender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shared deployment must not start with a sender that writes account links to
 * the log.
 *
 * <p>No Spring context here: the whole point is what happens before one comes
 * up, and this is the check itself rather than the wiring around it.
 */
class CloudReadinessTest {

    @Test
    void aSharedDeploymentRefusesToLogAccountLinks() {
        CloudReadiness readiness =
                new CloudReadiness(true, new LoggingVerificationLinkSender());

        Exception refused = assertThrows(IllegalStateException.class, readiness::afterPropertiesSet);

        assertTrue(refused.getMessage().contains("console.mail.host"),
                "the message has to say what to do, not only that something is missing");
    }

    @Test
    void aSharedDeploymentWithARealSenderStarts() {
        CloudReadiness readiness = new CloudReadiness(true, (email, token) -> {
        });

        assertDoesNotThrow(readiness::afterPropertiesSet);
    }

    /** One machine you own: logging the link is a convenience, not an exposure. */
    @Test
    void asingleMachineIsLeftAlone() {
        CloudReadiness readiness =
                new CloudReadiness(false, new LoggingVerificationLinkSender());

        assertDoesNotThrow(readiness::afterPropertiesSet);
    }
}
