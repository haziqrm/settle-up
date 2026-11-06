-- People, groups

CREATE TABLE users (
                       id           BIGSERIAL PRIMARY KEY,
                       display_name TEXT NOT NULL,
                       created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE groups (
                        id         BIGSERIAL PRIMARY KEY,
                        name       TEXT NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE group_members (
                               group_id  BIGINT NOT NULL REFERENCES groups(id),
                               user_id   BIGINT NOT NULL REFERENCES users(id),
                               role      TEXT NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('OWNER', 'MEMBER')),
                               joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                               PRIMARY KEY (group_id, user_id)
);

CREATE TABLE linked_accounts (
                                 id            BIGSERIAL PRIMARY KEY,
                                 user_id       BIGINT NOT NULL REFERENCES users(id),
                                 provider      TEXT NOT NULL,              -- e.g. FAKE, YAPILY, NATWEST
                                 consent_ref   TEXT,                       -- provider's consent/token reference
                                 receiving_ref TEXT,                       -- provider's reference for the account that RECEIVES money
                                 display_label TEXT NOT NULL,              -- e.g. 'NatWest ....4821'
                                 status        TEXT NOT NULL DEFAULT 'ACTIVE'
                                     CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED')),
                                 expires_at    TIMESTAMPTZ,
                                 created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_linked_accounts_user ON linked_accounts(user_id);

-- ---------------------------------------------------------------------------
-- Expenses
-- ---------------------------------------------------------------------------

CREATE TABLE expenses (
                          id           BIGSERIAL PRIMARY KEY,
                          group_id     BIGINT NOT NULL REFERENCES groups(id),
                          paid_by      BIGINT NOT NULL REFERENCES users(id),
                          description  TEXT NOT NULL,
                          amount_pence BIGINT NOT NULL CHECK (amount_pence > 0),
                          created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Settlement

CREATE TABLE settlement_runs (
                                 id          BIGSERIAL PRIMARY KEY,
                                 group_id    BIGINT NOT NULL REFERENCES groups(id),
                                 created_by  BIGINT NOT NULL REFERENCES users(id),
                                 status      TEXT NOT NULL DEFAULT 'OPEN'
                                     CHECK (status IN ('OPEN', 'COMPLETE', 'PARTIAL', 'CANCELLED')),
                                 deadline_at TIMESTAMPTZ NOT NULL,
                                 created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                                 updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Only one OPEN run per group at a time.
CREATE UNIQUE INDEX uq_one_open_run_per_group
    ON settlement_runs(group_id) WHERE status = 'OPEN';

CREATE TABLE payments (
                          id                  BIGSERIAL PRIMARY KEY,
                          run_id              BIGINT NOT NULL REFERENCES settlement_runs(id),
                          payer_id            BIGINT NOT NULL REFERENCES users(id),
                          payee_id            BIGINT NOT NULL REFERENCES users(id),
                          amount_pence        BIGINT NOT NULL CHECK (amount_pence > 0),

    -- PLANNED                 in the plan, payer has not acted yet
    -- AWAITING_AUTHORISATION  payment created, payer is at their bank approving it
    -- PENDING                 approved, waiting for the bank to confirm it settled
    -- CONFIRMED               money moved, ledger rows written
    -- FAILED                  bank rejected or errored
    -- EXPIRED                 payer never approved in time
    -- CANCELLED               payer or run cancelled it
                          status              TEXT NOT NULL DEFAULT 'PLANNED'
                              CHECK (status IN ('PLANNED', 'AWAITING_AUTHORISATION', 'PENDING',
                                                'CONFIRMED', 'FAILED', 'EXPIRED', 'CANCELLED')),

                          idempotency_key     TEXT NOT NULL UNIQUE,   -- e.g. 'run-7-ben-alex'; stops double payments on retry
                          provider            TEXT,                   -- which provider handled it
                          provider_payment_id TEXT,                   -- the provider's/bank's reference
                          auth_expires_at     TIMESTAMPTZ,            -- when the payer's approval window closes
                          failure_reason      TEXT,

                          created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                          updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

                          CHECK (payer_id <> payee_id),
                          UNIQUE (run_id, payer_id, payee_id)
);

CREATE UNIQUE INDEX uq_payment_provider_ref
    ON payments(provider, provider_payment_id) WHERE provider_payment_id IS NOT NULL;

-- Every webhook we receive is recorded once. Providers can deliver the same
-- event twice or out of order; the UNIQUE constraint makes duplicates harmless.
CREATE TABLE webhook_events (
                                id           BIGSERIAL PRIMARY KEY,
                                provider     TEXT NOT NULL,
                                event_id     TEXT NOT NULL,
                                payload      JSONB NOT NULL,
                                received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                                processed_at TIMESTAMPTZ,
                                UNIQUE (provider, event_id)
);

-- Ledger (append-only)

CREATE TABLE ledger_entries (
                                id           BIGSERIAL PRIMARY KEY,
                                group_id     BIGINT NOT NULL REFERENCES groups(id),
                                user_id      BIGINT NOT NULL REFERENCES users(id),
                                amount_pence BIGINT NOT NULL CHECK (amount_pence <> 0),
                                expense_id   BIGINT REFERENCES expenses(id),
                                payment_id   BIGINT REFERENCES payments(id),
                                created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

                                CHECK ((expense_id IS NOT NULL) <> (payment_id IS NOT NULL))
);

-- A payment can write at most one row per user, so a duplicate confirmation
-- cannot be recorded twice.
CREATE UNIQUE INDEX uq_ledger_one_row_per_payment_user
    ON ledger_entries(payment_id, user_id) WHERE payment_id IS NOT NULL;

CREATE INDEX idx_ledger_group_user ON ledger_entries(group_id, user_id);

-- Enforce "never edit or delete" in the database itself.
CREATE FUNCTION forbid_ledger_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only: add a correcting entry instead';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_change();