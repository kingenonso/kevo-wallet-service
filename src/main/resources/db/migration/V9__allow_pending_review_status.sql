ALTER TABLE withdrawals DROP CONSTRAINT withdrawals_status_check;
ALTER TABLE withdrawals ADD CONSTRAINT withdrawals_status_check
    CHECK (status IN ('PENDING','PENDING_REVIEW','COMPLETED','FAILED','REVERSED'));
