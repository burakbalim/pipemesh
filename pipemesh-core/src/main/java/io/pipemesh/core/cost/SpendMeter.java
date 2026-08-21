package io.pipemesh.core.cost;

import io.pipemesh.core.model.ModelId;
import io.pipemesh.core.state.StepRecord;

import java.util.Objects;

/**
 * Turns a finished step into what it added to an execution's spend (§39).
 *
 * <p>The cost is worked out when the step runs and written down then, never
 * derived later from the current price list — otherwise yesterday's execution
 * would report a different cost today, and "what did this run cost" would have
 * no stable answer.
 */
public final class SpendMeter {

    /** A meter that knows no prices: calls are counted, none of them priced. */
    public static final SpendMeter UNPRICED = new SpendMeter(ModelPrices.NONE);

    private final ModelPrices prices;

    public SpendMeter(ModelPrices prices) {
        this.prices = Objects.requireNonNull(prices, "prices");
    }

    /** The spend after this step, which is the spend before it if no model ran. */
    public Spend after(Spend spend, StepRecord step) {
        if (step.modelId() == null || step.modelId().isBlank()) {
            return spend;
        }
        ModelId model = ModelId.of(step.modelId());
        Money cost = prices.of(model)
                .map(price -> price.costOf(step.inputTokens(), step.outputTokens()))
                .orElse(null);

        return spend.plusModelCall(step.inputTokens(), step.outputTokens(), cost);
    }

    /**
     * Whether every model this budget could be spent on has a price.
     *
     * <p>Asked once, when a workflow is registered. A money budget that silently
     * ignores an unpriced model is not a budget.
     */
    public boolean canPrice(ModelId model) {
        return prices.knows(model);
    }
}
