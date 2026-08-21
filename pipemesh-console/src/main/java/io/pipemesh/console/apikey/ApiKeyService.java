package io.pipemesh.console.apikey;

import io.pipemesh.console.identity.Tokens;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Issuing, listing and revoking the keys an SDK authenticates with. */
@Service
public class ApiKeyService {

    /**
     * Marks the string as ours in a log or a leaked file, which is what makes an
     * automated secret scanner able to find it.
     */
    private static final String PREFIX = "pm_";

    /** Enough to pick one key out of a list, far too little to guess the rest. */
    private static final int VISIBLE_CHARACTERS = 8;

    private final ApiKeyRepository keys;
    private final Clock clock;

    public ApiKeyService(ApiKeyRepository keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    /**
     * Issues a key and returns its secret — once.
     *
     * <p>Nothing stores the secret, so this return value is the only chance
     * anybody has to keep it. A console that could show it again would be a
     * console whose own access is enough to take over every key it ever issued.
     */
    public IssuedApiKey issue(String organizationId, String name) {
        String secret = PREFIX + Tokens.generate();
        ApiKey key = new ApiKey(
                UUID.randomUUID().toString(),
                organizationId,
                name,
                secret.substring(0, PREFIX.length() + VISIBLE_CHARACTERS),
                clock.instant(),
                null,
                null);

        keys.insert(key, Tokens.hash(secret));
        return new IssuedApiKey(key, secret);
    }

    public List<ApiKey> list(String organizationId) {
        return keys.forOrganization(organizationId);
    }

    /** @return whether the key existed, was this organization's, and was live */
    public boolean revoke(String id, String organizationId) {
        return keys.revoke(id, organizationId, clock.instant());
    }

    /**
     * Who is presenting this key, if anybody.
     *
     * <p>The presented string is hashed and matched; nothing here compares
     * secrets, and a revoked key matches nothing at all.
     */
    public Optional<KeyHolder> holderOf(String presented) {
        return keys.holderOf(Tokens.hash(presented));
    }

    public void markUsed(String keyId) {
        keys.markUsed(keyId, clock.instant());
    }
}
