package com.kevo.wallet.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "bank_accounts")
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "account_holder_name", nullable = false, length = 200)
    private String accountHolderName;

    @Column(name = "bank_name", nullable = false, length = 200)
    private String bankName;

    @Column(name = "last_four", nullable = false, length = 4)
    private String lastFour;

    @Column(name = "provider_reference", nullable = false, unique = true, length = 200)
    private String providerReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BankAccountStatus status;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "disabled_at")
    private LocalDateTime disabledAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected BankAccount() {
    }

    public BankAccount(Long walletId, String accountHolderName, String bankName,
                        String lastFour, String providerReference) {
        this.walletId = walletId;
        this.accountHolderName = accountHolderName;
        this.bankName = bankName;
        this.lastFour = lastFour;
        this.providerReference = providerReference;
        this.status = BankAccountStatus.UNVERIFIED;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void markVerified() {
        this.status = BankAccountStatus.VERIFIED;
        this.verifiedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void markDisabled() {
        this.status = BankAccountStatus.DISABLED;
        this.disabledAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getWalletId() { return walletId; }
    public String getAccountHolderName() { return accountHolderName; }
    public String getBankName() { return bankName; }
    public String getLastFour() { return lastFour; }
    public String getProviderReference() { return providerReference; }
    public BankAccountStatus getStatus() { return status; }
    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public LocalDateTime getDisabledAt() { return disabledAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
