package io.pipemesh.core.cost;

import io.pipemesh.core.model.ModelId;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What each registered model charges.
 *
 * <p>A model with no price is <em>not</em> free. A local model genuinely costs
 * nothing, and someone forgetting to write the price down looks identical from
 * here — so the two are kept apart: unpriced usage is counted as unpriced, and a
 * workflow with a money budget is refused a model whose price nobody wrote
 * (§39).
 */
public final class ModelPrices {

    public static final ModelPrices NONE = new ModelPrices(Map.of());

    private final Map<ModelId, ModelPrice> prices;

    public ModelPrices(Map<ModelId, ModelPrice> prices) {
        this.prices = Map.copyOf(Objects.requireNonNull(prices, "prices"));
    }

    public Optional<ModelPrice> of(ModelId model) {
        return Optional.ofNullable(prices.get(model));
    }

    public boolean knows(ModelId model) {
        return prices.containsKey(model);
    }
}
