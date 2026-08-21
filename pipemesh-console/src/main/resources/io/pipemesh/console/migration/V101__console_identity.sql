-- Who may use this deployment (§23).
--
-- Console tables live beside the workflow ones in the same database and are
-- named apart: the console is an application that uses the runtime, and its
-- rows have no business being mistaken for the runtime's own.

CREATE TABLE console_plan (
    id               TEXT PRIMARY KEY,
    name             TEXT   NOT NULL,
    -- Zero means no limit, the same convention CostBudget uses (§39.1).
    max_executions   BIGINT NOT NULL DEFAULT 0,
    max_tokens       BIGINT NOT NULL DEFAULT 0,
    max_cost_micros  BIGINT NOT NULL DEFAULT 0,
    period_days      INT    NOT NULL DEFAULT 30,
    -- What an API key on this plan may do, as permission names a Principal
    -- carries. A plan is not only an amount; it is also a set of capabilities.
    permissions      TEXT[] NOT NULL DEFAULT '{}'
);

CREATE TABLE console_organization (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    plan_id     TEXT NOT NULL REFERENCES console_plan (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE console_user (
    id               TEXT PRIMARY KEY,
    organization_id  TEXT NOT NULL REFERENCES console_organization (id) ON DELETE CASCADE,
    -- Lowercased on the way in, so two people cannot own the same address by
    -- disagreeing about capitals.
    email            TEXT NOT NULL UNIQUE,
    password_hash    TEXT NOT NULL,
    verified_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The link is a bearer token: whoever holds it becomes the account. Only its
-- hash is stored, for the same reason a password is not stored in the clear.
CREATE TABLE console_verification (
    token_hash  TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL REFERENCES console_user (id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ
);

CREATE TABLE console_session (
    token_hash  TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL REFERENCES console_user (id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX console_user_by_organization ON console_user (organization_id);

-- The demo plan is a row, not a branch in the code: what people try has to be
-- the thing they would buy, and an `if (isDemo)` guarantees it is not.
INSERT INTO console_plan (id, name, max_executions, max_tokens, max_cost_micros, period_days, permissions)
VALUES ('demo', 'Demo', 50, 100000, 500000, 30, ARRAY['stream:watch']);
