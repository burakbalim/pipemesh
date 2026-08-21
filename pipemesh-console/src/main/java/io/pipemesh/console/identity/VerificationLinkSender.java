package io.pipemesh.console.identity;

/**
 * Delivers a verification link.
 *
 * <p>An interface because sending mail is a deployment's problem, not this
 * application's: in development the link goes to the log, in production to
 * whatever provider is configured. A registration flow that only works when SMTP
 * does is a registration flow that cannot be tested.
 */
@FunctionalInterface
public interface VerificationLinkSender {

    void send(String email, String token);
}
