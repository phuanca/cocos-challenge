-- Proposed account/ledger schema, kept separate from the challenge seed.
-- PostgreSQL is the durable source of truth. Redis is a rebuildable projection.

CREATE TABLE account_balances (
    account_id BIGINT PRIMARY KEY REFERENCES users(id),
    cash_balance NUMERIC(20, 2) NOT NULL DEFAULT 0,
    sequence BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_balances_cash_nonnegative CHECK (cash_balance >= 0),
    CONSTRAINT account_balances_sequence_nonnegative CHECK (sequence >= 0)
);

CREATE TABLE position_balances (
    account_id BIGINT NOT NULL REFERENCES users(id),
    instrument_id BIGINT NOT NULL REFERENCES instruments(id),
    settled_quantity NUMERIC(20, 0) NOT NULL DEFAULT 0,
    reserved_quantity NUMERIC(20, 0) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, instrument_id),
    CONSTRAINT position_balances_nonnegative CHECK (
        settled_quantity >= 0 AND reserved_quantity >= 0
    )
);

CREATE TABLE ledger_entries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES users(id),
    sequence BIGINT NOT NULL,
    operation_id UUID NOT NULL,
    order_id BIGINT REFERENCES orders(id),
    instrument_id BIGINT REFERENCES instruments(id),
    entry_type VARCHAR(32) NOT NULL,
    cash_delta NUMERIC(20, 2) NOT NULL DEFAULT 0,
    quantity_delta NUMERIC(20, 0) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ledger_entries_account_sequence_unique UNIQUE (account_id, sequence),
    CONSTRAINT ledger_entries_operation_unique UNIQUE (operation_id, entry_type),
    CONSTRAINT ledger_entries_has_effect CHECK (cash_delta <> 0 OR quantity_delta <> 0),
    CONSTRAINT ledger_entries_type_valid CHECK (entry_type IN (
        'CASH_CREDIT', 'CASH_DEBIT', 'TRADE_BUY', 'TRADE_SELL',
        'CASH_RESERVE', 'CASH_RELEASE', 'POSITION_RESERVE', 'POSITION_RELEASE',
        'ADJUSTMENT'
    )),
    CONSTRAINT ledger_entries_cash_shape CHECK (
        entry_type NOT IN ('CASH_CREDIT', 'CASH_DEBIT', 'CASH_RESERVE', 'CASH_RELEASE')
        OR (instrument_id IS NULL AND quantity_delta = 0)
    ),
    CONSTRAINT ledger_entries_position_shape CHECK (
        entry_type NOT IN ('TRADE_BUY', 'TRADE_SELL', 'POSITION_RESERVE', 'POSITION_RELEASE')
        OR instrument_id IS NOT NULL
    )
);

CREATE INDEX ledger_entries_account_created_idx
    ON ledger_entries (account_id, created_at DESC, id DESC);
CREATE INDEX ledger_entries_instrument_created_idx
    ON ledger_entries (instrument_id, created_at DESC, id DESC)
    WHERE instrument_id IS NOT NULL;
CREATE INDEX ledger_entries_order_idx
    ON ledger_entries (order_id)
    WHERE order_id IS NOT NULL;

CREATE TABLE operation_requests (
    operation_id UUID PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash BYTEA NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT operation_requests_idempotency_unique UNIQUE (account_id, idempotency_key),
    CONSTRAINT operation_requests_status_valid CHECK (status IN (
        'PENDING', 'APPLIED', 'REJECTED', 'FAILED'
    )),
    CONSTRAINT operation_requests_type_valid CHECK (operation_type IN (
        'ORDER', 'TRANSFER', 'CANCEL'
    ))
);

CREATE TABLE outbox_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES operation_requests(operation_id),
    account_id BIGINT NOT NULL REFERENCES users(id),
    sequence BIGINT NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    CONSTRAINT outbox_operation_event_unique UNIQUE (operation_id, event_type),
    CONSTRAINT outbox_account_sequence_unique UNIQUE (account_id, sequence),
    CONSTRAINT outbox_status_valid CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED')),
    CONSTRAINT outbox_attempts_nonnegative CHECK (attempts >= 0)
);

CREATE INDEX outbox_pending_idx
    ON outbox_events (available_at, id)
    WHERE status IN ('PENDING', 'FAILED');

-- Redis rebuild checkpoint: snapshot sequence per account. Rebuild each account
-- from account_balances/position_balances and apply subsequent ledger sequences.
CREATE TABLE projection_checkpoints (
    projection_name VARCHAR(64) NOT NULL,
    account_id BIGINT NOT NULL REFERENCES users(id),
    applied_sequence BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (projection_name, account_id),
    CONSTRAINT projection_checkpoint_nonnegative CHECK (applied_sequence >= 0)
);

-- Ledger rows are append-only for the application role. Enforce this for
-- accidental UPDATE/DELETE from ordinary SQL clients as well.
CREATE FUNCTION reject_ledger_entry_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries are append-only';
END;
$$;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_entry_mutation();
