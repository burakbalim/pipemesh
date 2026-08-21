package io.pipemesh.console.identity;

/** A verification link that cannot be claimed: used, expired or unknown. */
public class InvalidVerificationException extends RuntimeException {

    public InvalidVerificationException(String message) {
        super(message);
    }
}
