package io.pipemesh.console.identity;

import java.time.Instant;
import java.util.Optional;

/**
 * A person who can sign in.
 *
 * <p>{@code passwordHash} is what it says: the console never holds a password it
 * could hand back, and nothing here logs or returns this field.
 */
public record ConsoleUser(
        String id,
        String organizationId,
        String email,
        String passwordHash,
        Instant verifiedAt) {

    public boolean verified() {
        return verifiedAt != null;
    }

    public Optional<Instant> verifiedAtIfAny() {
        return Optional.ofNullable(verifiedAt);
    }
}
