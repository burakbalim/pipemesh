-- What a provider has told us about an organization's subscription (§39.1).
--
-- console_organization.plan_id stays the single source of truth for what an
-- organization may do. This table feeds it and never replaces it: the provider
-- is authoritative for whether a card cleared, we are authoritative for
-- entitlement, and asking the provider on every quota check would put an outside
-- service on the path of starting any workflow.

CREATE TABLE console_subscription (
    organization_id      TEXT PRIMARY KEY REFERENCES console_organization (id) ON DELETE CASCADE,
    -- The provider's own id for this subscription, so a webhook can be matched
    -- back without trusting anything in its body.
    provider_id          TEXT NOT NULL UNIQUE,
    plan_id              TEXT NOT NULL REFERENCES console_plan (id),
    status               TEXT NOT NULL,
    current_period_end   TIMESTAMPTZ,
    -- Webhooks arrive out of order. An older version never undoes a newer one.
    updated_version      BIGINT NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seen events, so the same one delivered twice changes nothing the second time.
-- The same shape as approval idempotency (§16): the record is what makes a
-- repeat harmless, not the caller's care.
CREATE TABLE console_payment_event (
    event_id     TEXT PRIMARY KEY,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
