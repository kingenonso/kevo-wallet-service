package com.kevo.wallet.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "withdrawals")
public class Withdrawal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "provider_reference", unique = true, length = 100)
    private String providerReference;

    @Column(name = "provider_name", nullable = false, length = 50)
    private String providerName;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "destination_reference", nullable = false, length = 200)
    private String destinationReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WithdrawalStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "ledger_entry_id")
    private Long ledgerEntryId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Withdrawal() {
    }

    public Withdrawal(Long walletId, String idempotencyKey, String providerReference,
                       String providerName, BigDecimal amount, String currency,
                       String destinationReference, WithdrawalStatus status,
                       String failureReason, Long ledgerEntryId) {
        this.walletId = walletId;
        this.idempotencyKey = idempotencyKey;
        this.providerReference = providerReference;
        this.providerName = providerName;
        this.amount = amount;
        this.currency = currency;
        this.destinationReference = destinationReference;
        this.status = status;
        this.failureReason = failureReason;
        this.ledgerEntryId = ledgerEntryId;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getWalletId() { return walletId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getProviderReference() { return providerReference; }
    public String getProviderName() { return providerName; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getDestinationReference() { return destinationReference; }
    public WithdrawalStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Long getLedgerEntryId() { return ledgerEntryId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
