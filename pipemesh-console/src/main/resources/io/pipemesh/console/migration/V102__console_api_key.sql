-- How an SDK proves which organization it is (§23).
--
-- The key itself is never stored. What is stored is its hash, plus a prefix
-- short enough to be useless and long enough to tell two keys apart on screen —
-- because the one thing a person needs from a list of keys is knowing which one
-- to revoke.

CREATE TABLE console_api_key (
    id               TEXT PRIMARY KEY,
    organization_id  TEXT NOT NULL REFERENCES console_organization (id) ON DELETE CASCADE,
    name             TEXT NOT NULL,
    key_hash         TEXT NOT NULL UNIQUE,
    prefix           TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at     TIMESTAMPTZ,
    revoked_at       TIMESTAMPTZ
);

-- The lookup on every single call the runtime receives.
CREATE INDEX console_api_key_live ON console_api_key (key_hash) WHERE revoked_at IS NULL;

CREATE INDEX console_api_key_by_organization ON console_api_key (organization_id);
