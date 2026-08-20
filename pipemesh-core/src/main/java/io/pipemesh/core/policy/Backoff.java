package io.pipemesh.core.policy;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** How long to wait before trying again. */
public enum Backoff {

    /** The same delay every time. */
    FIXED,

    /** Doubling, which is what a service under load needs callers to do. */
    EXPONENTIAL;

    public static Backoff of(String name) {
        return "fixed".equalsIgnoreCase(name) ? FIXED : EXPONENTIAL;
    }

    /**
     * The wait before {@code attempt} (1 is the first retry), capped at
     * {@code max}.
     *
     * <p>Jitter is not optional. Without it, every caller that failed at the same
     * moment retries at the same moment, and a service that just recovered is
     * knocked over by the crowd it kept waiting.
     */
    public Duration delayBefore(int attempt, Duration initial, Duration max) {
        long base = this == FIXED
                ? initial.toMillis()
                : initial.toMillis() * (1L << Math.min(attempt - 1, 20));

        long capped = Math.min(base, max.toMillis());
        long jittered = capped <= 0 ? 0 : ThreadLocalRandom.current().nextLong(capped / 2, capped + 1);
        return Duration.ofMillis(jittered);
    }
}
