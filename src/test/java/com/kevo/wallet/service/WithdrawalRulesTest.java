package com.kevo.wallet.service;

import com.kevo.wallet.entity.BankAccount;
import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.entity.Withdrawal;
import com.kevo.wallet.entity.WithdrawalStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JUnit coverage for WithdrawalService's business rules that were previously
 * only verified by hand: ownership/IDOR protection, the cooling-off window,
 * available-balance checks, and the daily withdrawal limit.
 * WithdrawalConcurrencyTest covers the race-condition side; this file covers
 * the sequential rule-by-rule behavior.
 */
@SpringBootTest
class WithdrawalRulesTest {

    @Autowired private WalletService walletService;
    @Autowired private DepositService depositService;
    @Autowired private BankAccountService bankAccountService;
    @Autowired private WithdrawalService withdrawalService;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private static final AtomicLong idCounter = new AtomicLong();
    private final List<Long> testWalletIds = new ArrayList<>();

    private Wallet newFundedWallet(BigDecimal depositAmount) {
        long kevoUserId = 910_000_000L + (System.currentTimeMillis() % 1_000_000) + idCounter.incrementAndGet();
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);
        testWalletIds.add(wallet.getId());
        if (depositAmount != null) {
            depositService.createDeposit(wallet, depositAmount, "USD", "rules-test-deposit-" + kevoUserId);
        }
        return wallet;
    }

    private BankAccount verifiedAccount(Wallet wallet) {
        BankAccount account = bankAccountService.addBankAccount(
                wallet.getId(), "Rules Test", "Test Bank", "00009876543210");
        bankAccountService.verifyBankAccount(account.getId(), wallet.getId());
        // Push verification back outside the 24-hour cooling-off window so the
        // withdrawal under test isn't blocked by cooling-off too.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "UPDATE bank_accounts SET verified_at = now() - interval '25 hours' WHERE id = :id")
                        .setParameter("id", account.getId())
                        .executeUpdate());
        return account;
    }

    @Test
    void withdrawalToBankAccountOwnedByAnotherWalletFails() {
        Wallet owner = newFundedWallet(new BigDecimal("100.00"));
        Wallet attacker = newFundedWallet(new BigDecimal("100.00"));
        BankAccount ownerAccount = verifiedAccount(owner);

        Withdrawal result = withdrawalService.createWithdrawal(
                attacker.getId(), new BigDecimal("10.00"), "USD",
                ownerAccount.getId(), "idor-test-" + UUID.randomUUID());

        assertEquals(WithdrawalStatus.FAILED, result.getStatus());
        assertEquals("Bank account does not belong to this wallet", result.getFailureReason());
    }

    @Test
    void withdrawalWithNonexistentBankAccountFails() {
        Wallet wallet = newFundedWallet(new BigDecimal("100.00"));

        Withdrawal result = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD",
                999_999_999L, "missing-account-test-" + UUID.randomUUID());

        assertEquals(WithdrawalStatus.FAILED, result.getStatus());
        assertEquals("Bank account not found", result.getFailureReason());
    }

    @Test
    void withdrawalFromUnverifiedBankAccountFails() {
        Wallet wallet = newFundedWallet(new BigDecimal("100.00"));
        BankAccount account = bankAccountService.addBankAccount(
                wallet.getId(), "Rules Test", "Test Bank", "00009876543210");

        Withdrawal result = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD",
                account.getId(), "unverified-test-" + UUID.randomUUID());

        assertEquals(WithdrawalStatus.FAILED, result.getStatus());
        assertEquals("Bank account is not verified", result.getFailureReason());
    }

    @Test
    void withdrawalDuringCoolingOffPeriodFails() {
        Wallet wallet = newFundedWallet(new BigDecimal("100.00"));
        BankAccount account = bankAccountService.addBankAccount(
                wallet.getId(), "Rules Test", "Test Bank", "00009876543210");
        bankAccountService.verifyBankAccount(account.getId(), wallet.getId());
        // No backdating here - verified_at is "now", so this account should
        // still be inside its 24-hour cooling-off window.

        Withdrawal result = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD",
                account.getId(), "coolingoff-test-" + UUID.randomUUID());

        assertEquals(WithdrawalStatus.FAILED, result.getStatus());
        assertEquals("Bank account is in its cooling-off period", result.getFailureReason());
    }

    @Test
    void withdrawalAfterCoolingOffPeriodSucceeds() {
        Wallet wallet = newFundedWallet(new BigDecimal("100.00"));
        BankAccount account = verifiedAccount(wallet);

        Withdrawal result = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD",
                account.getId(), "postcoolingoff-test-" + UUID.randomUUID());

        assertEquals(WithdrawalStatus.COMPLETED, result.getStatus());
    }

    @Test
    void withdrawalExceedingAvailableBalanceFails() {
        Wallet wallet = newFundedWallet(new BigDecimal("50.00"));
        BankAccount account = verifiedAccount(wallet);

        Withdrawal result = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("50.01"), "USD",
                account.getId(), "insufficient-test-" + UUID.randomUUID());

        assertEquals(WithdrawalStatus.FAILED, result.getStatus());
        assertEquals("Insufficient available funds", result.getFailureReason());
    }

    @Test
    void withdrawalExceedingDailyLimitFails() {
        Wallet wallet = newFundedWallet(new BigDecimal("6000.00"));
        BankAccount account = verifiedAccount(wallet);

        Withdrawal first = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("4990.00"), "USD",
                account.getId(), "daily-limit-first-" + UUID.randomUUID());
        assertEquals(WithdrawalStatus.COMPLETED, first.getStatus());

        Withdrawal second = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("20.00"), "USD",
                account.getId(), "daily-limit-second-" + UUID.randomUUID());

        assertEquals(WithdrawalStatus.FAILED, second.getStatus());
        assertEquals("Daily withdrawal limit exceeded", second.getFailureReason());
    }

    @Test
    void repeatedCallWithSameIdempotencyKeyReturnsSameWithdrawalWithoutReprocessing() {
        Wallet wallet = newFundedWallet(new BigDecimal("100.00"));
        BankAccount account = verifiedAccount(wallet);
        String key = "idempotency-test-" + UUID.randomUUID();

        Withdrawal first = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD", account.getId(), key);
        Withdrawal second = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD", account.getId(), key);

        assertEquals(first.getId(), second.getId());
        assertEquals(WithdrawalStatus.COMPLETED, second.getStatus());
    }

    @AfterEach
    void cleanup() {
        if (testWalletIds.isEmpty()) return;
        // Delete each table fully across ALL test wallets before moving to the
        // next table - some tests here deliberately create a withdrawal row
        // under one wallet that references another wallet's bank account (the
        // ownership/IDOR test), so per-wallet cascade order isn't safe.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM deposits WHERE wallet_id IN (:ids)")
                    .setParameter("ids", testWalletIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM withdrawals WHERE wallet_id IN (:ids)")
                    .setParameter("ids", testWalletIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM ledger_entries WHERE wallet_id IN (:ids)")
                    .setParameter("ids", testWalletIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM bank_accounts WHERE wallet_id IN (:ids)")
                    .setParameter("ids", testWalletIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM wallets WHERE id IN (:ids)")
                    .setParameter("ids", testWalletIds).executeUpdate();
        });
    }
}
