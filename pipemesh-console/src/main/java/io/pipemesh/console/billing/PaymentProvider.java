package io.pipemesh.console.billing;

/**
 * How the console reaches whoever takes the money.
 *
 * <p>An interface for the same reason a capability has one (§9.8): swapping the
 * provider must not touch the plan table, the quota check or any screen. It also
 * means a deployment can have none — an on-premise install is paid for by
 * contract, and there is nothing here for it to configure.
 */
public interface PaymentProvider {

    /**
     * Starts a purchase and returns where to send the person.
     *
     * @param returnUrl where the provider sends them back to
     */
    String checkoutUrl(String organizationId, String planId, String returnUrl);

    /**
     * Reads a webhook body into a subscription, or refuses it.
     *
     * @param signature the header the provider signed the body with
     * @param body      the <em>raw</em> body. Not a parsed object: a signature is
     *                  computed over the bytes that arrived, and anything
     *                  re-serialised on the way here will never match.
     * @throws InvalidWebhookException when the signature does not hold
     */
    PaymentEvent readWebhook(String signature, String body);

    /** Ends a subscription at the end of the period already paid for. */
    void cancelAtPeriodEnd(String providerId);
}
