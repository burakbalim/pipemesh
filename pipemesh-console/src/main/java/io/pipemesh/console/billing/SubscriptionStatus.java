package io.pipemesh.console.billing;

/** Where a subscription stands, as far as the provider has told us. */
public enum SubscriptionStatus {

    /** Paid and current. */
    ACTIVE,

    /**
     * A payment failed and the grace period has not run out.
     *
     * <p>Entitlement is unchanged here on purpose: a card that expired over a
     * weekend is not a reason to stop somebody's work.
     */
    PAST_DUE,

    /** Over, either by cancellation or by an unpaid grace period. */
    ENDED;

    public boolean entitled() {
        return this == ACTIVE || this == PAST_DUE;
    }
}
