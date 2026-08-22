package io.pipemesh.console.billing;

/**
 * One thing a provider told us, already verified.
 *
 * <p>{@code eventId} is the provider's, and is what makes a repeated delivery
 * harmless — it is stored, not compared to something we generated.
 */
public record PaymentEvent(String eventId, Subscription subscription) {
}
