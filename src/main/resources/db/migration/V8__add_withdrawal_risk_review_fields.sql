ALTER TABLE withdrawals ADD COLUMN risk_level VARCHAR(20);
ALTER TABLE withdrawals ADD COLUMN review_notes VARCHAR(1000);
ALTER TABLE withdrawals ADD COLUMN reviewed_at TIMESTAMP;
