package io.pipemesh.console.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Bearer tokens for verification links and sessions.
 *
 * <p>Generated from {@link SecureRandom} and stored only as a hash. A token in
 * the database is a token an operator, a backup or a leaked dump can use — and
 * unlike a password, nobody would ever notice it had been read.
 *
 * <p>SHA-256 rather than argon2 here, deliberately: these are 256 bits of
 * randomness, not something a person chose, so there is nothing to guess and
 * nothing for a slow hash to buy. A password is the opposite case and is hashed
 * the opposite way.
 */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BYTES = 32;

    private Tokens() {
    }

    public static String generate() {
        byte[] token = new byte[BYTES];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }
}
