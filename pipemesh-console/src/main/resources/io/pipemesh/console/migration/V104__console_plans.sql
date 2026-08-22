-- A plan for a deployment that does not meter anything (§39.1).
--
-- Zero means no limit, the same convention CostBudget uses, so an unlimited
-- plan is a row with zeros rather than a branch that skips the check. An
-- on-premise install is paid for by contract; the quota code still runs and
-- still finds nothing to refuse.

INSERT INTO console_plan (id, name, max_executions, max_tokens, max_cost_micros, period_days, permissions)
VALUES ('unlimited', 'Unlimited', 0, 0, 0, 30, ARRAY['stream:watch']);
