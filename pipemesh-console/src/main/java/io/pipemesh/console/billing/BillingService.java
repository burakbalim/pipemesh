package io.pipemesh.console.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Turns what a provider says into what an organization may do.
 *
 * <p>The split this rests on: the provider is authoritative for whether a card
 * cleared, and this application is authoritative for entitlement. Asking the
 * provider during a quota check would put an outside service on the path of
 * starting any workflow — when it is slow, nothing runs.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final BillingRepository subscriptions;
    private final Clock clock;
    private final String fallbackPlan;
    private final Duration grace;

    /**
     * @param fallbackPlan where an ended subscription lands. Not "no plan":
     *                     an organization always has one, so the quota code has
     *                     something to read and never a special case.
     * @param grace        how long an unpaid subscription keeps working. A
     *                     business decision, so it is configuration — a constant
     *                     here would take a deploy to change.
     */
    public BillingService(
            BillingRepository subscriptions, Clock clock,
            @Value("${console.billing.fallbackPlan:demo}") String fallbackPlan,
            @Value("${console.billing.gracePeriod:P7D}") Duration grace) {

        this.subscriptions = subscriptions;
        this.clock = clock;
        this.fallbackPlan = fallbackPlan;
        this.grace = grace;
    }

    public Optional<Subscription> of(String organizationId) {
        return subscriptions.find(organizationId);
    }

    /**
     * Applies a verified event, once.
     *
     * <p>Two guards, both in the store rather than in this method's care: the
     * event id is remembered so a repeat changes nothing, and the version is
     * compared in the write so an older event cannot undo a newer one. Providers
     * deliver duplicated and out of order; neither is an exception to handle
     * later.
     */
    @Transactional
    public void apply(PaymentEvent event) {
        if (!subscriptions.rememberEvent(event.eventId())) {
            log.debug("Payment event {} was already handled", event.eventId());
            return;
        }

        Subscription subscription = event.subscription();
        if (!subscriptions.save(subscription)) {
            log.info("Payment event {} was older than what is already known", event.eventId());
            return;
        }

        subscriptions.applyPlan(subscription.organizationId(), entitledPlan(subscription));
    }

    /**
     * Moves an organization down when its grace period has run out.
     *
     * <p>Narrowing a quota, and nothing else: no execution is deleted, no key is
     * revoked, no user is removed. Somebody who missed a payment has not stopped
     * being a customer, and what is deleted cannot be handed back.
     */
    @Transactional
    public boolean settleIfLapsed(String organizationId) {
        Optional<Subscription> known = subscriptions.find(organizationId);
        if (known.isEmpty() || !lapsed(known.get())) {
            return false;
        }

        subscriptions.applyPlan(organizationId, fallbackPlan);
        return true;
    }

    private boolean lapsed(Subscription subscription) {
        if (subscription.status() == SubscriptionStatus.ENDED) {
            return true;
        }
        if (subscription.status() != SubscriptionStatus.PAST_DUE) {
            return false;
        }
        return subscription.currentPeriodEnd() != null
                && subscription.currentPeriodEnd().plus(grace).isBefore(clock.instant());
    }

    private String entitledPlan(Subscription subscription) {
        return subscription.status().entitled() ? subscription.planId() : fallbackPlan;
    }
}
