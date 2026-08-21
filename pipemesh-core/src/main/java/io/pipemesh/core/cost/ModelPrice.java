package io.pipemesh.core.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * What one model charges, as registration metadata (§12).
 *
 * <p>A workflow never mentions money, exactly as it never mentions how a
 * capability is reached (§9.8). Prices change; registrations change with them and
 * no workflow is touched.
 */
public record ModelPrice(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    public ModelPrice {
        Objects.requireNonNull(inputPerMillion, "input price");
        Objects.requireNonNull(outputPerMillion, "output price");
        if (inputPerMillion.signum() < 0 || outputPerMillion.signum() < 0) {
            throw new IllegalArgumentException("a price must not be negative");
        }
    }

    public static ModelPrice of(String inputPerMillion, String outputPerMillion) {
        return new ModelPrice(new BigDecimal(inputPerMillion), new BigDecimal(outputPerMillion));
    }

    /** Rounded once, at the end, so a long run does not accumulate rounding. */
    public Money costOf(long inputTokens, long outputTokens) {
        BigDecimal cost = inputPerMillion.multiply(BigDecimal.valueOf(inputTokens))
                .add(outputPerMillion.multiply(BigDecimal.valueOf(outputTokens)))
                .divide(MILLION, 10, RoundingMode.HALF_UP);

        return new Money(cost.multiply(BigDecimal.valueOf(1_000_000))
                .setScale(0, RoundingMode.HALF_UP).longValueExact());
    }
}
