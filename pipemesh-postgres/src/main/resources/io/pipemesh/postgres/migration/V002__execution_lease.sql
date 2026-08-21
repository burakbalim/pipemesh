-- Who is currently driving an execution (§28).
--
-- A separate table on purpose: this is an operational fact about the deployment,
-- not state of the execution. Putting it on workflow_execution would put it in
-- every snapshot, every telemetry event and eventually on the wire, where it
-- means nothing to a caller.

CREATE TABLE workflow_lease (
    execution_id  TEXT PRIMARY KEY REFERENCES workflow_execution (execution_id) ON DELETE CASCADE,
    -- Names the instance, so "who is running this" is answerable from the
    -- database alone at three in the morning.
    owner         TEXT   NOT NULL,
    -- Distinguishes two claims by the same owner across a restart: a process that
    -- comes back with the same name must not renew a lease its previous life took.
    token         TEXT   NOT NULL,
    expires_at    BIGINT NOT NULL
);

-- The one lookup that happens on every dispatch round.
CREATE INDEX workflow_lease_expiring ON workflow_lease (expires_at);
