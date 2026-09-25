package com.kevo.wallet.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "fund_locks")
public class FundLock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "marketplace_transaction_id", nullable = false)
    private Long marketplaceTransactionId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FundLockStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "lock_ledger_entry_id")
    private Long lockLedgerEntryId;

    @Column(name = "release_ledger_entry_id")
    private Long releaseLedgerEntryId;

    @Column(name = "capture_ledger_entry_id")
    private Long captureLedgerEntryId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected FundLock() {
    }

    public FundLock(Long walletId, Long marketplaceTransactionId, String idempotencyKey,
                     BigDecimal amount, String currency, FundLockStatus status,
                     String failureReason, Long lockLedgerEntryId) {
        this.walletId = walletId;
        this.marketplaceTransactionId = marketplaceTransactionId;
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.failureReason = failureReason;
        this.lockLedgerEntryId = lockLedgerEntryId;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public void markReleased(Long releaseLedgerEntryId) {
        this.status = FundLockStatus.RELEASED;
        this.releaseLedgerEntryId = releaseLedgerEntryId;
        this.updatedAt = LocalDateTime.now();
    }

    public void markCaptured(Long captureLedgerEntryId) {
        this.status = FundLockStatus.CAPTURED;
        this.captureLedgerEntryId = captureLedgerEntryId;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getWalletId() { return walletId; }
    public Long getMarketplaceTransactionId() { return marketplaceTransactionId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public FundLockStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Long getLockLedgerEntryId() { return lockLedgerEntryId; }
    public Long getReleaseLedgerEntryId() { return releaseLedgerEntryId; }
    public Long getCaptureLedgerEntryId() { return captureLedgerEntryId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
