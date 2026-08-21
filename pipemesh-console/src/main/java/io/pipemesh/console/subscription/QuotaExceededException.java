package io.pipemesh.console.subscription;

/**
 * The organization has used up its plan for this period.
 *
 * <p>Distinct from a permission refusal: nothing is wrong with the caller or the
 * request, and the same call will work again when the period turns over. That
 * difference decides whether retrying is pointless or merely early.
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
