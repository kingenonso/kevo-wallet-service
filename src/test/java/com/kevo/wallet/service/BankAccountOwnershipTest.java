package com.kevo.wallet.service;

import com.kevo.wallet.entity.BankAccount;
import com.kevo.wallet.entity.Wallet;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JUnit coverage for BankAccountService's ownership checks and state
 * transitions, previously only proven by hand: a wallet can't verify or
 * disable another wallet's bank account, a disabled account can't be
 * re-verified, duplicate submissions of the same real account don't create
 * duplicate rows, and listings are scoped to the owning wallet.
 */
@SpringBootTest
class BankAccountOwnershipTest {

    @Autowired private WalletService walletService;
    @Autowired private BankAccountService bankAccountService;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private static final AtomicLong idCounter = new AtomicLong();
    private final List<Long> testWalletIds = new ArrayList<>();

    private Wallet newWallet() {
        long kevoUserId = 920_000_000L + (System.currentTimeMillis() % 1_000_000) + idCounter.incrementAndGet();
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);
        testWalletIds.add(wallet.getId());
        return wallet;
    }

    @Test
    void verifyingBankAccountOwnedByAnotherWalletFails() {
        Wallet owner = newWallet();
        Wallet attacker = newWallet();
        BankAccount account = bankAccountService.addBankAccount(
                owner.getId(), "Owner", "Test Bank", "00001111222233");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                bankAccountService.verifyBankAccount(account.getId(), attacker.getId()));
        assertEquals("Bank account not found for this wallet", ex.getMessage());
    }

    @Test
    void disablingBankAccountOwnedByAnotherWalletFails() {
        Wallet owner = newWallet();
        Wallet attacker = newWallet();
        BankAccount account = bankAccountService.addBankAccount(
                owner.getId(), "Owner", "Test Bank", "00001111222234");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                bankAccountService.disableBankAccount(account.getId(), attacker.getId()));
        assertEquals("Bank account not found for this wallet", ex.getMessage());
    }

    @Test
    void verifyingADisabledBankAccountFails() {
        Wallet wallet = newWallet();
        BankAccount account = bankAccountService.addBankAccount(
                wallet.getId(), "Owner", "Test Bank", "00001111222235");
        bankAccountService.disableBankAccount(account.getId(), wallet.getId());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                bankAccountService.verifyBankAccount(account.getId(), wallet.getId()));
        assertEquals("Cannot verify a disabled bank account", ex.getMessage());
    }

    @Test
    void addingBankAccountWithInvalidAccountNumberFails() {
        Wallet wallet = newWallet();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                bankAccountService.addBankAccount(wallet.getId(), "Owner", "Test Bank", "12"));
        assertEquals("Account number is invalid", ex.getMessage());
    }

    @Test
    void addingTheSameBankAccountTwiceReturnsTheExistingRowNotADuplicate() {
        Wallet wallet = newWallet();
        BankAccount first = bankAccountService.addBankAccount(
                wallet.getId(), "Owner", "Test Bank", "00001111222236");
        BankAccount second = bankAccountService.addBankAccount(
                wallet.getId(), "Owner", "Test Bank", "00001111222236");

        assertEquals(first.getId(), second.getId());
        assertEquals(1, bankAccountService.listBankAccounts(wallet.getId()).size());
    }

    @Test
    void listBankAccountsIsScopedToTheOwningWallet() {
        Wallet walletA = newWallet();
        Wallet walletB = newWallet();
        bankAccountService.addBankAccount(walletA.getId(), "A Owner", "Test Bank", "00001111222237");
        bankAccountService.addBankAccount(walletB.getId(), "B Owner", "Test Bank", "00001111222238");

        List<BankAccount> aAccounts = bankAccountService.listBankAccounts(walletA.getId());
        assertEquals(1, aAccounts.size());
        assertEquals("A Owner", aAccounts.get(0).getAccountHolderName());
    }

    @AfterEach
    void cleanup() {
        if (testWalletIds.isEmpty()) return;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (Long walletId : testWalletIds) {
                entityManager.createNativeQuery("DELETE FROM bank_accounts WHERE wallet_id = :id")
                        .setParameter("id", walletId).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM wallets WHERE id = :id")
                        .setParameter("id", walletId).executeUpdate();
            }
        });
    }
}
