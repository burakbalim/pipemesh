package io.pipemesh.console.apikey;

import java.time.Instant;

/**
 * A key as the console can show it: never the secret, only enough to tell one
 * from another.
 */
public record ApiKey(
        String id,
        String organizationId,
        String name,
        String prefix,
        Instant createdAt,
        Instant lastUsedAt,
        Instant revokedAt) {

    public boolean revoked() {
        return revokedAt != null;
    }
}
