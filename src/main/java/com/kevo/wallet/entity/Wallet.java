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

    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @Column(name = "stripe_connect_account_id")
    private String stripeConnectAccountId;

    @Column(name = "stripe_connect_payouts_enabled", nullable = false)
    private boolean stripeConnectPayoutsEnabled = false;

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
    public String getStripeCustomerId() { return stripeCustomerId; }

    // Stripe requires one real Customer object per person before it will
    // create a Financial Connections Session or Treasury account for them.
    // This links that Customer id to the wallet the first time one is
    // created, so later calls reuse it instead of creating duplicates.
    public void linkStripeCustomer(String stripeCustomerId) {
        this.stripeCustomerId = stripeCustomerId;
    }

    public String getStripeConnectAccountId() { return stripeConnectAccountId; }

    public boolean isStripeConnectPayoutsEnabled() { return stripeConnectPayoutsEnabled; }

    // Links this wallet to its Stripe Connect "Express" account the first
    // time one is created for the user, so later calls reuse it instead of
    // creating duplicates.
    public void linkStripeConnectAccount(String stripeConnectAccountId) {
        this.stripeConnectAccountId = stripeConnectAccountId;
    }

    // Flips to true only once Stripe itself confirms this user finished
    // their own KYC/bank-details onboarding and can actually receive real
    // payouts. Never set this directly from user input.
    public void markStripeConnectPayoutsEnabled() {
        this.stripeConnectPayoutsEnabled = true;
    }

    // Restricting a wallet blocks further withdrawals without touching its
    // funding ability - used when a deposit reversal/chargeback leaves the
    // wallet owing money back (spec Section 17), so more money can't leave
    // while that's unresolved.
    public void restrict() {
        this.status = WalletStatus.RESTRICTED;
    }
}
