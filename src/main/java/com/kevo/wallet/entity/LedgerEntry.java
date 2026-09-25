package com.kevo.wallet.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "ledger_transaction_id", nullable = false, length = 64)
    private String ledgerTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 6)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "bucket", nullable = false, length = 20)
    private Bucket bucket;

    @Column(name = "entry_type", nullable = false, length = 30)
    private String entryType;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "related_marketplace_transaction_id")
    private Long relatedMarketplaceTransactionId;

    @Column(name = "reverses_entry_id")
    private Long reversesEntryId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected LedgerEntry() {
        // required by JPA, not for application use
    }

    public LedgerEntry(Long walletId, String ledgerTransactionId, Direction direction,
                        Bucket bucket, String entryType, BigDecimal amount, String currency,
                        String description, Long relatedMarketplaceTransactionId, Long reversesEntryId) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Ledger entry amount must be positive");
        }
        this.walletId = walletId;
        this.ledgerTransactionId = ledgerTransactionId;
        this.direction = direction;
        this.bucket = bucket;
        this.entryType = entryType;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.relatedMarketplaceTransactionId = relatedMarketplaceTransactionId;
        this.reversesEntryId = reversesEntryId;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getWalletId() { return walletId; }
    public String getLedgerTransactionId() { return ledgerTransactionId; }
    public Direction getDirection() { return direction; }
    public Bucket getBucket() { return bucket; }
    public String getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public Long getRelatedMarketplaceTransactionId() { return relatedMarketplaceTransactionId; }
    public Long getReversesEntryId() { return reversesEntryId; }
    public Instant getCreatedAt() { return createdAt; }
}
