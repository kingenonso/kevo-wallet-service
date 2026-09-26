ALTER TABLE wallets ADD COLUMN stripe_connect_account_id VARCHAR(255);
ALTER TABLE wallets ADD COLUMN stripe_connect_payouts_enabled BOOLEAN NOT NULL DEFAULT FALSE;
