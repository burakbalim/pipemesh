package io.pipemesh.console.web;

import io.pipemesh.console.billing.BillingService;
import io.pipemesh.console.billing.PaymentEvent;
import io.pipemesh.console.billing.PaymentProvider;
import io.pipemesh.console.billing.Subscription;
import io.pipemesh.console.identity.ConsoleUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Buying a plan, and hearing back about it.
 *
 * <p>The whole controller is conditional on a provider existing. Not "returns an
 * error without one" — <em>absent</em>: an install with no payment configured
 * must not have a webhook endpoint at all, because an endpoint that answers 200
 * to an unsigned POST looks exactly like one that is working.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnBean(PaymentProvider.class)
public class BillingController {

    private final PaymentProvider payments;
    private final BillingService billing;
    private final String returnUrl;

    public BillingController(
            PaymentProvider payments, BillingService billing,
            @Value("${console.baseUrl}") String returnUrl) {

        this.payments = payments;
        this.billing = billing;
        this.returnUrl = returnUrl;
    }

    public record CheckoutRequest(String planId) {
    }

    public record CheckoutView(String url) {
    }

    public record SubscriptionView(String planId, String status, String currentPeriodEnd) {
    }

    @PostMapping("/checkout")
    public CheckoutView checkout(ConsoleUser user, @RequestBody CheckoutRequest request) {
        return new CheckoutView(
                payments.checkoutUrl(user.organizationId(), request.planId(), returnUrl));
    }

    @GetMapping("/subscription")
    public SubscriptionView subscription(ConsoleUser user) {
        Optional<Subscription> known = billing.of(user.organizationId());
        return known
                .map(subscription -> new SubscriptionView(
                        subscription.planId(),
                        subscription.status().name(),
                        String.valueOf(subscription.currentPeriodEnd())))
                .orElse(new SubscriptionView(null, "NONE", null));
    }

    @PostMapping("/subscription/cancel")
    public void cancel(ConsoleUser user) {
        billing.of(user.organizationId()).ifPresent(
                // At the end of the period, not now: the time has been paid for.
                subscription -> payments.cancelAtPeriodEnd(subscription.providerId()));
    }

    /**
     * What the provider calls.
     *
     * <p>No session: the caller is the provider, and its identity is the
     * signature rather than a cookie. The body arrives as a raw string because a
     * signature covers the bytes that were sent — handing Jackson a chance to
     * re-serialise them means the check can never match, which is the quiet way
     * this ends up disabled.
     */
    @PostMapping(value = "/webhooks/payment", consumes = MediaType.ALL_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void webhook(
            @RequestHeader(name = "X-Payment-Signature", required = false) String signature,
            @RequestBody String body) {

        PaymentEvent event = payments.readWebhook(signature, body);
        billing.apply(event);
    }
}
