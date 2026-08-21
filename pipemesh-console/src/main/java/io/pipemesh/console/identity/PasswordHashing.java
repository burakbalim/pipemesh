package io.pipemesh.console.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * How passwords are stored.
 *
 * <p>Argon2id, with the parameters written down rather than defaulted: they are
 * the only thing standing between a leaked table and every account in it, and a
 * value nobody chose is a value nobody can defend. These are OWASP's second
 * option — 19 MiB of memory, two passes — which is the one that fits a JVM
 * serving requests.
 */
@Configuration
public class PasswordHashing {

    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19 * 1024;
    private static final int ITERATIONS = 2;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(
                SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);
    }
}
