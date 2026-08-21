package io.pipemesh.core.cost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * What an execution has spent so far (§39).
 *
 * <p>Unlike a lease (§28.1), this <em>is</em> state of the execution: the
 * decision it feeds is the execution's own, it has to survive a restart, and
 * "what did this run cost" is a question about the execution rather than about
 * the deployment. So it lives on the record and appears in a snapshot.
 *
 * <p>{@code unpricedCalls} is deliberately separate from {@code cost}. Adding a
 * priced and an unpriced call together would produce a number that reads like
 * the whole cost and is not.
 */
public record Spend(long modelCalls, long unpricedCalls, long tokens, Money cost) {

    public static final Spend NOTHING = new Spend(0, 0, 0, Money.ZERO);

    private static final String MODEL_CALLS = "modelCalls";
    private static final String UNPRICED_CALLS = "unpricedCalls";
    private static final String TOKENS = "tokens";
    private static final String COST_MICROS = "costMicros";

    public Spend {
        Objects.requireNonNull(cost, "cost");
    }

    /**
     * Records one model call.
     *
     * @param cost {@code null} when the model has no registered price — the call
     *             is still counted, it simply cannot be priced
     */
    public Spend plusModelCall(long inputTokens, long outputTokens, Money cost) {
        return new Spend(
                modelCalls + 1,
                cost == null ? unpricedCalls + 1 : unpricedCalls,
                tokens + inputTokens + outputTokens,
                cost == null ? this.cost : this.cost.plus(cost));
    }

    public ObjectNode toJson() {
        return JsonNodeFactory.instance.objectNode()
                .put(MODEL_CALLS, modelCalls)
                .put(UNPRICED_CALLS, unpricedCalls)
                .put(TOKENS, tokens)
                .put(COST_MICROS, cost.micros());
    }

    public static Spend fromJson(JsonNode json) {
        if (json == null || !json.isObject()) {
            return NOTHING;
        }
        return new Spend(
                json.path(MODEL_CALLS).asLong(0),
                json.path(UNPRICED_CALLS).asLong(0),
                json.path(TOKENS).asLong(0),
                new Money(json.path(COST_MICROS).asLong(0)));
    }
}
