package com.kevo.wallet.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kevo_user_id", nullable = false, unique = true)
    private Long kevoUserId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WalletStatus status = WalletStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Wallet() {
        // required by JPA, not for application use
    }

    public Wallet(Long kevoUserId, String currency) {
        this.kevoUserId = kevoUserId;
        this.currency = currency;
        this.status = WalletStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getKevoUserId() { return kevoUserId; }
    public String getCurrency() { return currency; }
    public WalletStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    // Restricting a wallet blocks further withdrawals without touching its
    // funding ability - used when a deposit reversal/chargeback leaves the
    // wallet owing money back (spec Section 17), so more money can't leave
    // while that's unresolved.
    public void restrict() {
        this.status = WalletStatus.RESTRICTED;
    }
}
