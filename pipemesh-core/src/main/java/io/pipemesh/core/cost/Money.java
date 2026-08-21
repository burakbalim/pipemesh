package io.pipemesh.core.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount of money, in millionths of a unit (§39).
 *
 * <p>Integer arithmetic, because what is being added up here is exactly money.
 * Floating point drifts, and a budget that stops an execution has to be able to
 * say what it counted.
 *
 * <p>Micros rather than cents because per-token prices are far below a cent: a
 * model at $1.25 per million input tokens costs 1.25 micros per token.
 */
public record Money(long micros) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    private static final BigDecimal PER_UNIT = BigDecimal.valueOf(1_000_000);

    /**
     * Reads an amount written the way a person writes one: {@code "2.50"}.
     *
     * <p>Parsed as a decimal, never as a double — {@code 2.50} is not
     * representable in binary floating point, and the error is systematic.
     */
    public static Money parse(String amount) {
        Objects.requireNonNull(amount, "amount");
        BigDecimal value = new BigDecimal(amount.trim());
        if (value.signum() < 0) {
            throw new IllegalArgumentException("money must not be negative: " + amount);
        }
        return new Money(value.multiply(PER_UNIT).setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(micros, other.micros));
    }

    public boolean isZero() {
        return micros == 0;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(micros, other.micros);
    }

    /** Two decimal places, which is how the amount was written and read. */
    @Override
    public String toString() {
        return BigDecimal.valueOf(micros).divide(PER_UNIT).setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }
}
