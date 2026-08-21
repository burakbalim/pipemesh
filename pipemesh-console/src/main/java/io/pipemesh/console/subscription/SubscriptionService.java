package io.pipemesh.console.subscription;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * What a plan allows, and whether an organization is still inside it.
 *
 * <p>None of this reaches the engine. The runtime knows nothing about
 * subscriptions and must not (§3); this is a gate in front of it.
 */
@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptions;
    private final Clock clock;

    public SubscriptionService(SubscriptionRepository subscriptions, Clock clock) {
        this.subscriptions = subscriptions;
        this.clock = clock;
    }

    public Optional<Plan> planOf(String organizationId) {
        return subscriptions.planOf(organizationId);
    }

    public Usage usageOf(String organizationId) {
        Plan plan = subscriptions.planOf(organizationId).orElseThrow(
                () -> new IllegalStateException("organization " + organizationId + " has no plan"));

        Instant start = periodStart(organizationId, plan);
        return subscriptions.usageSince(
                start, start.plus(Duration.ofDays(plan.periodDays())), organizationId);
    }

    /**
     * Refuses when the plan is used up.
     *
     * <p>Checked before work begins rather than while it runs: an execution
     * stopped halfway has already spent whatever it spent, and the caller is left
     * with a half-finished run instead of a clear refusal. This is also the one
     * difference from a workflow's own budget, which necessarily stops mid-run
     * because that is when it finds out (§39.1).
     */
    public void refuseIfExhausted(String organizationId) {
        Plan plan = subscriptions.planOf(organizationId).orElseThrow(
                () -> new IllegalStateException("organization " + organizationId + " has no plan"));

        Usage usage = usageOf(organizationId);

        if (over(plan.maxExecutions(), usage.executions())) {
            throw new QuotaExceededException(
                    "this plan allows " + plan.maxExecutions() + " executions per period");
        }
        if (over(plan.maxTokens(), usage.tokens())) {
            throw new QuotaExceededException(
                    "this plan allows " + plan.maxTokens() + " tokens per period");
        }
        if (over(plan.maxCostMicros(), usage.costMicros())) {
            throw new QuotaExceededException("this plan's spend for the period is used up");
        }
    }

    /**
     * Periods run from the day the organization was created, not from the
     * calendar month: a month boundary would give somebody who signed up on the
     * 30th a period lasting a day.
     */
    private Instant periodStart(String organizationId, Plan plan) {
        Instant createdAt = subscriptions.organizationCreatedAt(organizationId)
                .orElseThrow(() -> new IllegalStateException("no such organization"));

        Duration period = Duration.ofDays(plan.periodDays());
        long elapsed = Duration.between(createdAt, clock.instant()).toSeconds();
        long periods = elapsed / period.toSeconds();
        return createdAt.plus(period.multipliedBy(periods));
    }

    /**
     * Zero means no limit.
     *
     * <p>{@code >=} rather than {@code >}, and the difference from §39.1 is the
     * direction of the question. A workflow budget asks afterwards — "did what
     * ran overrun?" — so landing exactly on the limit is fine. This asks
     * beforehand: already at fifty of fifty means the next one is the fifty-first.
     */
    private static boolean over(long limit, long used) {
        return limit > 0 && used >= limit;
    }
}
