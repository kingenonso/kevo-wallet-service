CREATE TABLE wallets (
    id BIGSERIAL PRIMARY KEY,
    kevo_user_id BIGINT NOT NULL UNIQUE,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    wallet_id BIGINT NOT NULL REFERENCES wallets(id),
    ledger_transaction_id VARCHAR(64) NOT NULL,
    direction VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    bucket VARCHAR(20) NOT NULL CHECK (bucket IN ('AVAILABLE','PENDING','LOCKED','WITHDRAWAL_PENDING')),
    entry_type VARCHAR(30) NOT NULL,
    amount NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    description VARCHAR(500),
    related_marketplace_transaction_id BIGINT,
    reverses_entry_id BIGINT REFERENCES ledger_entries(id),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_entries_wallet_id ON ledger_entries(wallet_id);
CREATE INDEX idx_ledger_entries_ledger_transaction_id ON ledger_entries(ledger_transaction_id);
