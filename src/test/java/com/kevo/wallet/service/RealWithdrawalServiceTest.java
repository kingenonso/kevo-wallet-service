package com.kevo.wallet.service;

import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.entity.Withdrawal;
import com.kevo.wallet.entity.WithdrawalStatus;
import com.kevo.wallet.repository.WalletRepository;
import com.kevo.wallet.repository.WithdrawalRepository;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers RealWithdrawalService's guard-rail checks - the parts that fail
 * fast before ever calling Stripe (not onboarded yet, wallet restricted,
 * insufficient funds, daily limit, HIGH-risk is refused rather than queued
 * for review, idempotency). The actual Transfer+Payout call path to Stripe
 * itself is deliberately NOT covered here - same as RealDepositService and
 * StripeConnectService, which also have no automated Stripe-calling tests
 * yet; that path was verified by hand in test mode, not in CI.
 */
@SpringBootTest
class RealWithdrawalServiceTest {

    @Autowired private WalletService walletService;
    @Autowired private DepositService depositService;
    @Autowired private WalletRepository walletRepository;
    @Autowired private RealWithdrawalService realWithdrawalService;
    @Autowired private WithdrawalRepository withdrawalRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private static final AtomicLong idCounter = new AtomicLong();
    private final List<Long> testWalletIds = new ArrayList<>();

    private Wallet newFundedWallet(BigDecimal depositAmount) {
        long kevoUserId = 920_000_000L + (System.currentTimeMillis() % 1_000_000) + idCounter.incrementAndGet();
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);
        testWalletIds.add(wallet.getId());
        if (depositAmount != null) {
            depositService.createDeposit(wallet, depositAmount, "USD", "real-wd-rules-test-deposit-" + kevoUserId);
        }
        return wallet;
    }

    // No real Stripe call is made anywhere in this test file - this just
    // marks the wallet as if onboarding had already completed, the same
    // way the test suite backdates cooling-off instead of waiting 24 hours.
    private Wallet withFakeConnectAccountReady(Wallet wallet) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Wallet fresh = walletRepository.findById(wallet.getId()).orElseThrow();
            fresh.linkStripeConnectAccount("acct_test_" + wallet.getId());
            fresh.markStripeConnectPayoutsEnabled();
            walletRepository.save(fresh);
        });
        return wallet;
    }

    @Test
    void withdrawalWithoutCompletedStripeOnboardingFails() {
        Wallet wallet = newFundedWallet(new BigDecimal("100.00"));

        Withdrawal result = realWithdrawalService.createRealWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD", "real-no-onboarding-" + UUID.randomUUID());

        assertEquals(WithdrawalStatus.FAILED, result.getStatus());
        assertEquals("Stripe payout onboarding is not complete yet - finish connecting a payout account first",
                result.getFailureReason());
        assertNull(result.getBankAccountId());
    }

    @Test
    void withdrawalFromRestrictedWalletFails() {
        Wallet wallet = newFundedWallet(new BigDecimal("100.00"));
        withFakeConnectAccountReady(wallet);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Wallet fresh = walletRepository.findById(wallet.getId()).orElseThrow();
            fresh.restrict();
            walletRepository.save(fresh);
        });

        Withdrawal result = realWithdrawalService.createRealWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD", "real-restricted-" + UUID.randomUUID());

        assertEquals(WithdrawalStatus.FAILED, result.getStatus());
        assertEquals("Wallet is restricted from withdrawals", result.getFailureReason());
    }

    @Test
    void withdrawalExceedingAvailableBalanceFails() {
        Wallet wallet = newFundedWallet(new BigDecimal("50.00"));
        withFakeConnectAccountReady(wallet);

        Withdrawal result = realWithdrawalService.createRealWithdrawal(
                wallet.getId(), new BigDecimal("50.01"), "USD", "real-insufficient-" + UUID.randomUUID());

        assertEquals(WithdrawalStatus.FAILED, result.getStatus());
        assertEquals("Insufficient available funds", result.getFailureReason());
    }

    @Test
    void highRiskRealWithdrawalIsRefusedWithoutEverCallingStripe() {
        // Fakes 3 prior FAILED withdrawals directly (bypassing Stripe
        // entirely) within the last hour, which on its own sets
        // repeatedFailures=true in WithdrawalRiskService - and that
        // unconditionally scores HIGH (see the "repeatedFailures ||
        // flags.size() >= 2" check), independent of how many individual
        // flags fire. This avoids relying on NEW_DESTINATION_ACCOUNT's
        // behavior with a null bankAccountId (Spring Data JPA translates a
        // null equality parameter to "IS NULL", so every real-path
        // withdrawal - which always passes bankAccountId=null - actually
        // matches every other real-path withdrawal's null destination,
        // meaning NEW_DESTINATION_ACCOUNT does NOT reliably fire the way an
        // earlier version of this test assumed). If this test reached
        // Stripe with "acct_test_fake" it would fail for the wrong reason
        // (an invalid fake account) rather than prove the risk-refusal path
        // actually works.
        Wallet wallet = newFundedWallet(new BigDecimal("1000.00"));
        withFakeConnectAccountReady(wallet);
        for (int i = 0; i < 3; i++) {
            Withdrawal priorFailed = new Withdrawal(wallet.getId(), "real-risk-setup-" + UUID.randomUUID(),
                    null, "STRIPE_CONNECT", new BigDecimal("5.00"), "USD",
                    null, "Stripe Connect payout to acct_test_fake", WithdrawalStatus.FAILED,
                    "setup failure " + i, null);
            withdrawalRepository.save(priorFailed);
        }

        Withdrawal result = realWithdrawalService.createRealWithdrawal(
                wallet.getId(), new BigDecimal("20.00"), "USD", "real-high-risk-" + UUID.randomUUID());

        assertEquals(WithdrawalStatus.FAILED, result.getStatus());
        assertEquals("STRIPE_CONNECT", result.getProviderName());
        assertNull(result.getProviderReference());
        assertTrue(result.getFailureReason().contains("HIGH"), "failureReason was: " + result.getFailureReason());
    }

    @Test
    void repeatedCallWithSameIdempotencyKeyReturnsSameWithdrawalWithoutReprocessing() {
        Wallet wallet = newFundedWallet(new BigDecimal("100.00"));
        String key = "real-idempotency-test-" + UUID.randomUUID();

        Withdrawal first = realWithdrawalService.createRealWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD", key);
        Withdrawal second = realWithdrawalService.createRealWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD", key);

        assertEquals(first.getId(), second.getId());
        assertEquals(first.getStatus(), second.getStatus());
    }

    @AfterEach
    void cleanup() {
        if (testWalletIds.isEmpty()) return;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM deposits WHERE wallet_id IN (:ids)")
                    .setParameter("ids", testWalletIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM withdrawals WHERE wallet_id IN (:ids)")
                    .setParameter("ids", testWalletIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM ledger_entries WHERE wallet_id IN (:ids)")
                    .setParameter("ids", testWalletIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM wallets WHERE id IN (:ids)")
                    .setParameter("ids", testWalletIds).executeUpdate();
        });
    }
}
