-- Durable execution state (§15). The only reason a workflow survives a restart.

CREATE TABLE workflow_execution (
    execution_id      TEXT PRIMARY KEY,
    -- Carried from the first write: it decides which rows a query may return and
    -- which series a metric lands in, and adding it later means migrating every
    -- row and re-labelling every dashboard.
    organization_id   TEXT        NOT NULL DEFAULT 'default',
    workflow_id       TEXT        NOT NULL,
    workflow_version  TEXT        NOT NULL,
    status            TEXT        NOT NULL,
    current_step      TEXT,
    variables         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    trace_context     TEXT        NOT NULL DEFAULT '',
    -- Optimistic locking. Two workers reading the same execution must not both
    -- advance it: the loser's UPDATE matches no row and is rejected as stale.
    version           BIGINT      NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX workflow_execution_waiting
    ON workflow_execution (organization_id, status, updated_at)
    WHERE status = 'WAITING';

-- Recovery scans for executions a dead process left running, so this is the one
-- index a sweep depends on.
CREATE INDEX workflow_execution_running
    ON workflow_execution (status, updated_at)
    WHERE status = 'RUNNING';

CREATE INDEX workflow_execution_by_organization
    ON workflow_execution (organization_id, workflow_id, created_at DESC);

CREATE TABLE workflow_step_history (
    id                BIGSERIAL PRIMARY KEY,
    execution_id      TEXT        NOT NULL REFERENCES workflow_execution (execution_id),
    step_id           TEXT        NOT NULL,
    step_type         TEXT        NOT NULL,
    outcome           TEXT        NOT NULL,
    input_snapshot    JSONB,
    output_snapshot   JSONB,
    model_id          TEXT,
    prompt_version    TEXT,
    input_tokens      BIGINT      NOT NULL DEFAULT 0,
    output_tokens     BIGINT      NOT NULL DEFAULT 0,
    latency_ms        BIGINT      NOT NULL DEFAULT 0,
    attempt           INT         NOT NULL DEFAULT 1,
    -- Whatever the step reported about itself. Typed columns above are the
    -- handful worth indexing; this keeps the rest without inventing a column
    -- every time a new step type has something to say.
    attributes        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    started_at        TIMESTAMPTZ NOT NULL,
    finished_at       TIMESTAMPTZ NOT NULL
);

CREATE INDEX workflow_step_history_execution
    ON workflow_step_history (execution_id, id);

CREATE TABLE workflow_approval (
    approval_id       TEXT PRIMARY KEY,
    execution_id      TEXT        NOT NULL REFERENCES workflow_execution (execution_id),
    step_id           TEXT        NOT NULL,
    message           TEXT        NOT NULL DEFAULT '',
    status            TEXT        NOT NULL,
    decided_by        TEXT,
    comment           TEXT,
    requested_at      TIMESTAMPTZ NOT NULL,
    decided_at        TIMESTAMPTZ,
    expires_at        TIMESTAMPTZ
);

CREATE INDEX workflow_approval_pending
    ON workflow_approval (execution_id)
    WHERE status = 'PENDING';
