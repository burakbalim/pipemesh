package io.pipemesh.console.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the link to the log instead of sending it.
 *
 * <p>The default, and only because nothing better is configured. It is loud
 * about that: a deployment running on this is one where anybody who can read the
 * logs can take over any account being registered.
 *
 * <p>A deployment that sends real mail contributes its own sender marked
 * {@code @Primary}. Not {@code @ConditionalOnMissingBean} — that only means
 * anything inside auto-configuration, and a conditional that quietly does
 * nothing is worse than no conditional at all.
 */
@Component
public class LoggingVerificationLinkSender implements VerificationLinkSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingVerificationLinkSender.class);

    @Override
    public void send(String email, String token) {
        log.warn("No mail sender configured. Verification link for {}: /verify?token={}", email, token);
    }
}
