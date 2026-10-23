package com.kevo.wallet.service;

import com.kevo.wallet.entity.BankAccount;
import com.kevo.wallet.entity.Deposit;
import com.kevo.wallet.entity.DepositStatus;
import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.repository.BankAccountRepository;
import com.kevo.wallet.repository.WalletRepository;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers RealDepositService's guard-rail checks and its idempotency
 * check - the parts that fail fast (or short-circuit) before ever calling
 * Stripe. The actual PaymentIntent-creation call path to Stripe itself is
 * deliberately NOT covered here - same as RealWithdrawalService's
 * Transfer+Payout path and StripeConnectService, which also have no
 * automated Stripe-calling tests; that path was verified by hand in test
 * mode, not in CI.
 */
@SpringBootTest
class RealDepositServiceTest {

    @Autowired private WalletService walletService;
    @Autowired private WalletRepository walletRepository;
    @Autowired private BankAccountRepository bankAccountRepository;
    @Autowired private RealDepositService realDepositService;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private static final AtomicLong idCounter = new AtomicLong();
    private final List<Long> testWalletIds = new ArrayList<>();

    private Wallet newWallet() {
        long kevoUserId = 930_000_000L + (System.currentTimeMillis() % 1_000_000) + idCounter.incrementAndGet();
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);
        testWalletIds.add(wallet.getId());
        return wallet;
    }

    private BankAccount newBankAccount(Long walletId, boolean verified, boolean withPaymentMethod) {
        BankAccount account = new BankAccount(walletId, "Test Holder", "Test Bank", "6789",
                "fc_test_ref_" + UUID.randomUUID());
        if (verified) {
            account.markVerified();
        }
        if (withPaymentMethod) {
            account.linkStripePaymentMethod("pm_test_" + UUID.randomUUID());
        }
        return bankAccountRepository.save(account);
    }

    @Test
    void depositAgainstUnknownBankAccountFails() {
        Wallet wallet = newWallet();

        assertThrows(IllegalArgumentException.class, () ->
                realDepositService.createRealDeposit(wallet.getId(), 999_999_999L, 1000L,
                        "real-deposit-unknown-bank-" + UUID.randomUUID()));
    }

    @Test
    void depositAgainstUnverifiedBankAccountFails() {
        Wallet wallet = newWallet();
        BankAccount account = newBankAccount(wallet.getId(), false, false);

        assertThrows(IllegalStateException.class, () ->
                realDepositService.createRealDeposit(wallet.getId(), account.getId(), 1000L,
                        "real-deposit-unverified-" + UUID.randomUUID()));
    }

    @Test
    void depositAgainstVerifiedBankAccountWithNoPaymentMethodFails() {
        Wallet wallet = newWallet();
        BankAccount account = newBankAccount(wallet.getId(), true, false);

        assertThrows(IllegalStateException.class, () ->
                realDepositService.createRealDeposit(wallet.getId(), account.getId(), 1000L,
                        "real-deposit-no-pm-" + UUID.randomUUID()));
    }

    @Test
    void depositForWalletWithNoStripeCustomerFails() {
        Wallet wallet = newWallet();
        BankAccount account = newBankAccount(wallet.getId(), true, true);
        // newWallet() never links a Stripe customer, so this should fail on
        // that check even though the bank account itself is fully ready.

        assertThrows(IllegalStateException.class, () ->
                realDepositService.createRealDeposit(wallet.getId(), account.getId(), 1000L,
                        "real-deposit-no-customer-" + UUID.randomUUID()));
    }

    @Test
    void repeatedCallWithSameIdempotencyKeyReturnsSameDepositWithoutCallingStripeAgain() {
        // Inserts the "already processed" deposit directly, bypassing
        // Stripe entirely - if the idempotency check didn't short-circuit
        // before the bank-account lookup, this would throw
        // IllegalArgumentException for the bogus bankAccountId instead of
        // returning the existing row.
        Wallet wallet = newWallet();
        String key = "real-deposit-idempotency-" + UUID.randomUUID();
        Deposit existing = new Deposit(wallet.getId(), key, "pi_fake_" + UUID.randomUUID(), "STRIPE",
                new BigDecimal("25.00"), "usd", DepositStatus.PENDING, null);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.persist(existing));

        Deposit result = assertDoesNotThrowReturning(() ->
                realDepositService.createRealDeposit(wallet.getId(), 999_999_999L, 2500L, key));

        assertEquals(existing.getId(), result.getId());
        assertEquals(DepositStatus.PENDING, result.getStatus());
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private <T> T assertDoesNotThrowReturning(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new AssertionError("Expected no exception but got: " + e, e);
        }
    }

    @AfterEach
    void cleanup() {
        if (testWalletIds.isEmpty()) return;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM deposits WHERE wallet_id IN (:ids)")
                    .setParameter("ids", testWalletIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM bank_accounts WHERE wallet_id IN (:ids)")
                    .setParameter("ids", testWalletIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM wallets WHERE id IN (:ids)")
                    .setParameter("ids", testWalletIds).executeUpdate();
        });
    }
}
