package io.pipemesh.console.subscription;

import java.time.Instant;

/**
 * What an organization has used this period.
 *
 * <p>Derived from the executions themselves rather than kept as a counter: the
 * runtime already writes what every run spent, and a second copy is two numbers
 * that eventually disagree.
 */
public record Usage(
        Instant periodStart,
        Instant periodEnd,
        long executions,
        long tokens,
        long costMicros) {
}
