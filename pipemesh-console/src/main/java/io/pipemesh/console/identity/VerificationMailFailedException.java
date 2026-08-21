package io.pipemesh.console.identity;

/** The account exists but its link could not be sent. */
public class VerificationMailFailedException extends RuntimeException {

    public VerificationMailFailedException(String email, Throwable cause) {
        super("could not send the verification link to " + email, cause);
    }
}
