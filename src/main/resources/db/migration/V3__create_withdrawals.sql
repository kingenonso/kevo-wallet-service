CREATE TABLE withdrawals (
    id BIGSERIAL PRIMARY KEY,
    wallet_id BIGINT NOT NULL REFERENCES wallets(id),
    idempotency_key VARCHAR(100) NOT NULL,
    provider_reference VARCHAR(100) UNIQUE,
    provider_name VARCHAR(50) NOT NULL,
    amount NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    destination_reference VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','COMPLETED','FAILED','REVERSED')),
    failure_reason VARCHAR(500),
    ledger_entry_id BIGINT REFERENCES ledger_entries(id),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (wallet_id, idempotency_key)
);
