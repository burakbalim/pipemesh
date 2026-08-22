package io.pipemesh.console.billing;

/**
 * A webhook that did not carry a valid signature.
 *
 * <p>The endpoint takes an unauthenticated POST. Without this check anybody
 * could upgrade themselves, which walks around the entire permission model
 * (§23) with one HTTP call.
 */
public class InvalidWebhookException extends RuntimeException {

    public InvalidWebhookException(String message) {
        super(message);
    }
}
