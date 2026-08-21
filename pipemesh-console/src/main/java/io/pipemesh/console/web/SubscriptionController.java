package io.pipemesh.console.web;

import io.pipemesh.console.identity.ConsoleUser;
import io.pipemesh.console.subscription.Plan;
import io.pipemesh.console.subscription.SubscriptionRepository;
import io.pipemesh.console.subscription.SubscriptionService;
import io.pipemesh.console.subscription.Usage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** What this organization's plan allows, and what it has used. */
@RestController
@RequestMapping("/api/v1")
public class SubscriptionController {

    private final SubscriptionService subscriptions;
    private final SubscriptionRepository plans;

    public SubscriptionController(
            SubscriptionService subscriptions, SubscriptionRepository plans) {

        this.subscriptions = subscriptions;
        this.plans = plans;
    }

    public record UsageView(Plan plan, Usage used) {
    }

    @GetMapping("/usage")
    public UsageView usage(ConsoleUser user) {
        return new UsageView(
                subscriptions.planOf(user.organizationId()).orElseThrow(),
                subscriptions.usageOf(user.organizationId()));
    }

    @GetMapping("/plans")
    public List<Plan> plans() {
        return plans.plans();
    }
}
