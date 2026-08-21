package io.pipemesh.console.apikey;

/**
 * A key at the one moment its secret exists outside the holder's hands.
 *
 * <p>Returned by issuing and never again: the console keeps a hash, so a key
 * that is lost is replaced rather than recovered. That is a property worth
 * having — a key the console could hand back is a key anyone with console
 * access could take.
 */
public record IssuedApiKey(ApiKey key, String secret) {
}
