package io.pipemesh.console.web;

/**
 * No such key here.
 *
 * <p>The same answer for a key that never existed and one belonging to another
 * organization: telling them apart would let anybody with an account probe for
 * key ids.
 */
public class UnknownApiKeyException extends RuntimeException {

    public UnknownApiKeyException(String id) {
        super("no key '" + id + "' on this organization");
    }
}
