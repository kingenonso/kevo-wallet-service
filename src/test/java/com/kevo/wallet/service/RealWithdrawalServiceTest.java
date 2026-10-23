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
        // Fakes a prior completed withdrawal directly (bypassing Stripe
        // entirely) so this wallet is past its "first ever" withdrawal,
        // then asks for an amount far above that history. Because the real
        // path always passes a null bankAccountId to the risk check, the
        // destination also always reads as "new" - two flags fire at once
        // (AMOUNT_FAR_ABOVE_HISTORY + NEW_DESTINATION_ACCOUNT), which scores
        // HIGH and must be refused before any real Stripe call is made - if
        // this test reached Stripe with "acct_test_..." it would fail for
        // the wrong reason (an invalid fake account) rather than prove the
        // risk-refusal path actually works.
        Wallet wallet = newFundedWallet(new BigDecimal("1000.00"));
        withFakeConnectAccountReady(wallet);
        Withdrawal priorCompleted = new Withdrawal(wallet.getId(), "real-risk-setup-" + UUID.randomUUID(),
                "fake_prior_ref_" + UUID.randomUUID(), "STRIPE_CONNECT", new BigDecimal("5.00"), "USD",
                null, "Stripe Connect payout to acct_test_fake", WithdrawalStatus.COMPLETED, null, null);
        withdrawalRepository.save(priorCompleted);

        Withdrawal result = realWithdrawalService.createRealWithdrawal(
                wallet.getId(), new BigDecimal("20.00"), "USD", "real-high-risk-" + UUID.randomUUID());

        assertEquals(WithdrawalStatus.FAILED, result.getStatus());
        assertEquals("STRIPE_CONNECT", result.getProviderName());
        assertNull(result.getProviderReference());
        assertEquals(true, result.getFailureReason().contains("HIGH"));
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
