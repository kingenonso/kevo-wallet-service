package com.kevo.wallet.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "deposits")
public class Deposit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "provider_reference", nullable = false, unique = true, length = 100)
    private String providerReference;

    @Column(name = "provider_name", nullable = false, length = 50)
    private String providerName;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DepositStatus status;

    @Column(name = "ledger_entry_id")
    private Long ledgerEntryId;

    // Set only by markReversed() - the compensating DEBIT ledger entry
    // that reversed this deposit (see DepositReversalService). Distinct
    // from ledgerEntryId, which keeps pointing at the ORIGINAL completion
    // entry even after a reversal - this column exists so a later
    // reinstatement knows exactly which reversal entry it's undoing.
    @Column(name = "reversal_ledger_entry_id")
    private Long reversalLedgerEntryId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Deposit() {}

    public Deposit(Long walletId, String idempotencyKey, String providerReference, String providerName,
                    BigDecimal amount, String currency, DepositStatus status, Long ledgerEntryId) {
        this.walletId = walletId;
        this.idempotencyKey = idempotencyKey;
        this.providerReference = providerReference;
        this.providerName = providerName;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.ledgerEntryId = ledgerEntryId;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getWalletId() { return walletId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getProviderReference() { return providerReference; }
    public String getProviderName() { return providerName; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public DepositStatus getStatus() { return status; }
    public Long getLedgerEntryId() { return ledgerEntryId; }
    public Long getReversalLedgerEntryId() { return reversalLedgerEntryId; }
    public Instant getCreatedAt() { return createdAt; }

    // A reversed deposit is never edited or deleted - this just flips its own
    // status; the actual balance change happens via a new compensating ledger
    // entry (see DepositReversalService), never by touching this row's amount.
    // reversalLedgerEntryId records that compensating entry's id, so a later
    // reinstatement (dispute resolved in KEVO's favor) can find it again.
    public void markReversed(Long reversalLedgerEntryId) {
        this.status = DepositStatus.REVERSED;
        this.reversalLedgerEntryId = reversalLedgerEntryId;
    }

    // Reinstating a REVERSED deposit after its dispute is resolved in
    // KEVO's favor (charge.dispute.funds_reinstated) is the same status/
    // ledger-entry shape as completing a real deposit in the first place -
    // COMPLETED plus a definitive ledger entry id - so this deliberately
    // reuses markCompleted() rather than adding a near-duplicate method.
    // The actual REVERSED-only precondition is enforced by
    // DepositReversalService.reinstateDeposit(), not here.

    // Completing a real (Stripe-backed) deposit happens asynchronously, once
    // a webhook confirms the money actually cleared - so unlike the demo
    // flow, this deposit already exists as PENDING before it can be marked
    // COMPLETED. The actual ledger entry is created separately by
    // LedgerPostingService; this method only records the outcome here.
    public void markCompleted(Long ledgerEntryId) {
        this.status = DepositStatus.COMPLETED;
        this.ledgerEntryId = ledgerEntryId;
    }

    // A real deposit can fail after being started (insufficient funds,
    // account closed, etc). No ledger entry is ever created for a failed
    // deposit, so there is no balance change to reverse.
    public void markFailed() {
        this.status = DepositStatus.FAILED;
    }
}
