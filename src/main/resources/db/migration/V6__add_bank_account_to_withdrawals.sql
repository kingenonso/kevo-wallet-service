ALTER TABLE withdrawals ADD COLUMN bank_account_id BIGINT REFERENCES bank_accounts(id);
CREATE INDEX idx_withdrawals_bank_account_id ON withdrawals(bank_account_id);
