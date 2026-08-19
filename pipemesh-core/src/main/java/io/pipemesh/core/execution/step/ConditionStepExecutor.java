package io.pipemesh.core.execution.step;

import io.pipemesh.core.execution.ExecutionContext;
import io.pipemesh.core.execution.StepExecutor;
import io.pipemesh.core.execution.StepResult;
import io.pipemesh.core.expression.ConditionExpression;
import io.pipemesh.core.expression.ExpressionException;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;

import java.util.List;

/**
 * Branches on a deterministic comparison (§9.3).
 *
 * <p>No model is consulted. A decision that can be made from data already in the
 * context is made from data already in the context — that is the whole point of
 * §20.
 *
 * <pre>
 * { "type": "condition", "expression": "$.request.valid == true",
 *   "onTrue": "search_venue", "onFalse": "rejected" }
 * </pre>
 */
public final class ConditionStepExecutor implements StepExecutor {

    private static final String EXPRESSION = "expression";
    private static final String ON_TRUE = "onTrue";
    private static final String ON_FALSE = "onFalse";

    @Override
    public boolean supports(StepType type) {
        return StepType.CONDITION.equals(type);
    }

    @Override
    public StepResult execute(Step step, ExecutionContext context) {
        String expression = step.config().path(EXPRESSION).asText("");
        if (expression.isBlank()) {
            return new StepResult.Failed("condition.missing_expression",
                    "step " + step.id() + " has no expression", false);
        }
        try {
            boolean matched = ConditionExpression.parse(expression).evaluate(context.variables());
            return StepResult.Continue.to(branch(step, matched));
        } catch (ExpressionException invalid) {
            return new StepResult.Failed("condition.invalid_expression", invalid.getMessage(), false);
        }
    }

    @Override
    public List<StepId> outgoing(Step step) {
        return Stepwiring.stepIds(step, ON_TRUE, ON_FALSE);
    }

    private StepId branch(Step step, boolean matched) {
        String target = step.config().path(matched ? ON_TRUE : ON_FALSE).asText("");
        if (target.isBlank()) {
            throw new IllegalStateException(
                    "step " + step.id() + " has no '" + (matched ? ON_TRUE : ON_FALSE) + "' target");
        }
        return StepId.of(target);
    }
}
