package io.pipemesh.console.subscription;

import java.util.List;

/**
 * What a subscription includes: how much, and what.
 *
 * <p>Zero means no limit, the same convention {@code CostBudget} uses (§39.1),
 * so a plan and a workflow budget read the same way.
 */
public record Plan(
        String id,
        String name,
        long maxExecutions,
        long maxTokens,
        long maxCostMicros,
        int periodDays,
        List<String> permissions) {

    public Plan {
        permissions = List.copyOf(permissions == null ? List.of() : permissions);
    }
}
