CREATE TABLE fund_locks (
    id BIGSERIAL PRIMARY KEY,
    wallet_id BIGINT NOT NULL REFERENCES wallets(id),
    marketplace_transaction_id BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    amount NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','RELEASED','CAPTURED','FAILED')),
    failure_reason VARCHAR(500),
    lock_ledger_entry_id BIGINT REFERENCES ledger_entries(id),
    release_ledger_entry_id BIGINT REFERENCES ledger_entries(id),
    capture_ledger_entry_id BIGINT REFERENCES ledger_entries(id),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (wallet_id, idempotency_key)
);

CREATE INDEX idx_fund_locks_wallet_marketplace_tx ON fund_locks(wallet_id, marketplace_transaction_id);
