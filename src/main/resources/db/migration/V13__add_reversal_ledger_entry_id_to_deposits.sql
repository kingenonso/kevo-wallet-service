-- Added for real Stripe refund/dispute handling (2026-10-26):
-- DepositReversalService.reverseDeposit() creates a compensating DEBIT
-- ledger entry when a COMPLETED deposit is reversed, but deposits.
-- ledger_entry_id still points at the ORIGINAL completion entry (that
-- column's existing meaning is left alone on purpose). This new column
-- records the reversal entry itself, so a later reinstatement (e.g. KEVO
-- wins a Stripe dispute, funds.reinstated fires) can create its own
-- compensating CREDIT entry that references exactly which reversal it's
-- undoing, rather than guessing from ledger_entry_id.
ALTER TABLE deposits ADD COLUMN reversal_ledger_entry_id BIGINT REFERENCES ledger_entries(id);
