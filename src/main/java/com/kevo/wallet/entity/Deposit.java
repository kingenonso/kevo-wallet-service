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
    public Instant getCreatedAt() { return createdAt; }
}
