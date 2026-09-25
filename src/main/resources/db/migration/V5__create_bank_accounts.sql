CREATE TABLE bank_accounts (
    id BIGSERIAL PRIMARY KEY,
    wallet_id BIGINT NOT NULL REFERENCES wallets(id),
    account_holder_name VARCHAR(200) NOT NULL,
    bank_name VARCHAR(200) NOT NULL,
    last_four VARCHAR(4) NOT NULL,
    provider_reference VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('UNVERIFIED', 'VERIFIED', 'DISABLED')),
    verified_at TIMESTAMP,
    disabled_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_bank_accounts_provider_reference UNIQUE (provider_reference)
);

CREATE INDEX idx_bank_accounts_wallet_id ON bank_accounts(wallet_id);
