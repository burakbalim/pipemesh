package io.pipemesh.core.cost;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;

/**
 * What one execution may spend (§39).
 *
 * <p>The same decision this codebase keeps making: a step budget, an agent turn
 * limit, a worker deadline, a wait timeout. Money was the missing member of that
 * list — an agent that loops stops at its turn limit, while a workflow sending
 * long context ten times over had nothing stopping it.
 *
 * <p>Zero means unlimited, and that is the default: a workflow without a budget
 * behaves exactly as it did before.
 */
public record CostBudget(Money maxCost, long maxModelCalls, long maxTokens) {

    public static final CostBudget UNLIMITED = new CostBudget(Money.ZERO, 0, 0);

    public CostBudget {
        Objects.requireNonNull(maxCost, "max cost");
        if (maxModelCalls < 0 || maxTokens < 0) {
            throw new IllegalArgumentException("a budget limit must not be negative");
        }
    }

    public static CostBudget fromJson(JsonNode budget) {
        if (budget == null || !budget.isObject()) {
            return UNLIMITED;
        }
        String maxCost = budget.path("maxCost").asText("");
        return new CostBudget(
                maxCost.isBlank() ? Money.ZERO : Money.parse(maxCost),
                budget.path("maxModelCalls").asLong(0),
                budget.path("maxTokens").asLong(0));
    }

    public boolean limitsMoney() {
        return !maxCost.isZero();
    }

    public boolean isUnlimited() {
        return !limitsMoney() && maxModelCalls == 0 && maxTokens == 0;
    }

    /**
     * What this budget has to say about a spend, if anything.
     *
     * <p>Landing exactly on a limit is not overrunning it — the same distinction
     * the step budget already makes, and the same mistake if it is missed.
     */
    public Optional<String> exceededBy(Spend spend) {
        if (limitsMoney() && spend.cost().compareTo(maxCost) > 0) {
            return Optional.of("cost " + spend.cost() + " exceeds budget " + maxCost);
        }
        if (maxModelCalls > 0 && spend.modelCalls() > maxModelCalls) {
            return Optional.of(spend.modelCalls() + " model calls exceed budget " + maxModelCalls);
        }
        if (maxTokens > 0 && spend.tokens() > maxTokens) {
            return Optional.of(spend.tokens() + " tokens exceed budget " + maxTokens);
        }
        return Optional.empty();
    }
}
