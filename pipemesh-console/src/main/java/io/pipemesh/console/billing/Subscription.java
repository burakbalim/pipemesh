package io.pipemesh.console.billing;

import java.time.Instant;

/**
 * What a provider says about one organization.
 *
 * <p>{@code version} is how out-of-order delivery is survived: a webhook
 * carrying an older version never undoes a newer one, and providers do not
 * promise order.
 */
public record Subscription(
        String organizationId,
        String providerId,
        String planId,
        SubscriptionStatus status,
        Instant currentPeriodEnd,
        long version) {
}
