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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec Section 15 (Withdrawal Security) - risk scoring and manual review.
 * Covers WithdrawalRiskService's real, binary flags and how WithdrawalService
 * wires them in (holding HIGH-risk withdrawals as PENDING_REVIEW instead of
 * processing them), plus the admin approve/reject actions on a held one.
 */
@SpringBootTest
class WithdrawalRiskTest {

    @Autowired private WalletService walletService;
    @Autowired private DepositService depositService;
    @Autowired private BankAccountService bankAccountService;
    @Autowired private WithdrawalService withdrawalService;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private static final AtomicLong idCounter = new AtomicLong();
    private final List<Long> testWalletIds = new ArrayList<>();

    private Wallet newFundedWallet(BigDecimal depositAmount) {
        long kevoUserId = 920_000_000L + (System.currentTimeMillis() % 1_000_000) + idCounter.incrementAndGet();
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);
        testWalletIds.add(wallet.getId());
        if (depositAmount != null) {
            depositService.createDeposit(wallet, depositAmount, "USD", "risk-test-deposit-" + kevoUserId);
        }
        return wallet;
    }

    private BankAccount verifiedAccount(Wallet wallet, String lastFour) {
        BankAccount account = bankAccountService.addBankAccount(
                wallet.getId(), "Risk Test", "Test Bank", "0000987654" + lastFour);
        bankAccountService.verifyBankAccount(account.getId(), wallet.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "UPDATE bank_accounts SET verified_at = now() - interval '25 hours' WHERE id = :id")
                        .setParameter("id", account.getId())
                        .executeUpdate());
        return account;
    }

    @Test
    void establishedWalletWithdrawingToNewDestinationIsElevatedButNotBlocked() {
        Wallet wallet = newFundedWallet(new BigDecimal("500.00"));
        BankAccount accountA = verifiedAccount(wallet, "01");
        Withdrawal history = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD", accountA.getId(), "risk-history-" + UUID.randomUUID());
        assertEquals(WithdrawalStatus.COMPLETED, history.getStatus());

        BankAccount accountB = verifiedAccount(wallet, "02");
        Withdrawal result = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("15.00"), "USD", accountB.getId(), "risk-newdest-" + UUID.randomUUID());

        assertEquals(WithdrawalStatus.COMPLETED, result.getStatus());
        assertEquals("ELEVATED", result.getRiskLevel());
        assertTrue(result.getReviewNotes().contains("NEW_DESTINATION_ACCOUNT"));
    }

    @Test
    void newDestinationCombinedWithLargeAmountIsHeldForReview() {
        Wallet wallet = newFundedWallet(new BigDecimal("5000.00"));
        BankAccount accountA = verifiedAccount(wallet, "03");
        Withdrawal history = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD", accountA.getId(), "risk-history2-" + UUID.randomUUID());
        assertEquals(WithdrawalStatus.COMPLETED, history.getStatus());

        BankAccount accountB = verifiedAccount(wallet, "04");
        Withdrawal result = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("100.00"), "USD", accountB.getId(), "risk-bigamount-" + UUID.randomUUID());

        assertEquals(WithdrawalStatus.PENDING_REVIEW, result.getStatus());
        assertEquals("HIGH", result.getRiskLevel());
        assertNull(result.getProviderReference());
        assertNull(result.getLedgerEntryId());
        assertTrue(result.getReviewNotes().contains("NEW_DESTINATION_ACCOUNT"));
        assertTrue(result.getReviewNotes().contains("AMOUNT_FAR_ABOVE_HISTORY"));
    }

    @Test
    void firstWithdrawalAloneIsElevatedNotBlocked() {
        Wallet wallet = newFundedWallet(new BigDecimal("100.00"));
        BankAccount account = verifiedAccount(wallet, "05");

        Withdrawal result = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD", account.getId(), "risk-firstonly-" + UUID.randomUUID());

        assertEquals(WithdrawalStatus.COMPLETED, result.getStatus());
        assertEquals("ELEVATED", result.getRiskLevel());
        assertEquals("FIRST_WITHDRAWAL_EVER: This would be the wallet's first-ever completed withdrawal.",
                result.getReviewNotes());
    }

    @Test
    void firstWithdrawalNearDailyLimitIsHeldForReview() {
        Wallet wallet = newFundedWallet(new BigDecimal("5000.00"));
        BankAccount account = verifiedAccount(wallet, "06");

        Withdrawal result = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("4600.00"), "USD", account.getId(), "risk-firstnearlimit-" + UUID.randomUUID());

        assertEquals(WithdrawalStatus.PENDING_REVIEW, result.getStatus());
        assertEquals("HIGH", result.getRiskLevel());
        assertTrue(result.getReviewNotes().contains("FIRST_WITHDRAWAL_EVER"));
        assertTrue(result.getReviewNotes().contains("NEAR_DAILY_LIMIT"));
    }

    @Test
    void repeatedRecentFailuresAloneTriggersHighRisk() {
        Wallet wallet = newFundedWallet(new BigDecimal("20.00"));
        BankAccount account = verifiedAccount(wallet, "07");

        for (int i = 0; i < 3; i++) {
            Withdrawal failed = withdrawalService.createWithdrawal(
                    wallet.getId(), new BigDecimal("999.00"), "USD", account.getId(), "risk-fail-" + i + "-" + UUID.randomUUID());
            assertEquals(WithdrawalStatus.FAILED, failed.getStatus());
        }

        Withdrawal result = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD", account.getId(), "risk-afterfails-" + UUID.randomUUID());

        assertEquals(WithdrawalStatus.PENDING_REVIEW, result.getStatus());
        assertEquals("HIGH", result.getRiskLevel());
        assertTrue(result.getReviewNotes().contains("REPEATED_RECENT_FAILURES"));
    }

    @Test
    void approvingAPendingReviewWithdrawalProcessesItAndCreatesLedgerEntry() {
        Wallet wallet = newFundedWallet(new BigDecimal("5000.00"));
        BankAccount accountA = verifiedAccount(wallet, "08");
        withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD", accountA.getId(), "risk-approve-history-" + UUID.randomUUID());
        BankAccount accountB = verifiedAccount(wallet, "09");
        Withdrawal held = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("100.00"), "USD", accountB.getId(), "risk-approve-held-" + UUID.randomUUID());
        assertEquals(WithdrawalStatus.PENDING_REVIEW, held.getStatus());

        Withdrawal approved = withdrawalService.approveReviewedWithdrawal(held.getId(), wallet.getId());

        assertEquals(WithdrawalStatus.COMPLETED, approved.getStatus());
        assertNotNull(approved.getProviderReference());
        assertNotNull(approved.getLedgerEntryId());
        assertNotNull(approved.getReviewedAt());
    }

    @Test
    void rejectingAPendingReviewWithdrawalMarksFailedWithReason() {
        Wallet wallet = newFundedWallet(new BigDecimal("5000.00"));
        BankAccount accountA = verifiedAccount(wallet, "10");
        withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD", accountA.getId(), "risk-reject-history-" + UUID.randomUUID());
        BankAccount accountB = verifiedAccount(wallet, "11");
        Withdrawal held = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("100.00"), "USD", accountB.getId(), "risk-reject-held-" + UUID.randomUUID());
        assertEquals(WithdrawalStatus.PENDING_REVIEW, held.getStatus());

        Withdrawal rejected = withdrawalService.rejectReviewedWithdrawal(
                held.getId(), wallet.getId(), "Confirmed suspicious by admin");

        assertEquals(WithdrawalStatus.FAILED, rejected.getStatus());
        assertEquals("Confirmed suspicious by admin", rejected.getFailureReason());
        assertNotNull(rejected.getReviewedAt());
    }

    @Test
    void approvingAnotherWalletsHeldWithdrawalFailsOwnershipCheck() {
        Wallet owner = newFundedWallet(new BigDecimal("5000.00"));
        Wallet stranger = newFundedWallet(new BigDecimal("100.00"));
        BankAccount accountA = verifiedAccount(owner, "12");
        withdrawalService.createWithdrawal(
                owner.getId(), new BigDecimal("10.00"), "USD", accountA.getId(), "risk-idor-history-" + UUID.randomUUID());
        BankAccount accountB = verifiedAccount(owner, "13");
        Withdrawal held = withdrawalService.createWithdrawal(
                owner.getId(), new BigDecimal("100.00"), "USD", accountB.getId(), "risk-idor-held-" + UUID.randomUUID());
        assertEquals(WithdrawalStatus.PENDING_REVIEW, held.getStatus());

        assertThrows(IllegalArgumentException.class, () ->
                withdrawalService.approveReviewedWithdrawal(held.getId(), stranger.getId()));
    }

    @Test
    void cannotApproveAWithdrawalThatIsNotPendingReview() {
        Wallet wallet = newFundedWallet(new BigDecimal("100.00"));
        BankAccount account = verifiedAccount(wallet, "14");
        Withdrawal completed = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD", account.getId(), "risk-notpending-" + UUID.randomUUID());
        assertEquals(WithdrawalStatus.COMPLETED, completed.getStatus());

        assertThrows(IllegalStateException.class, () ->
                withdrawalService.approveReviewedWithdrawal(completed.getId(), wallet.getId()));
    }

    @Test
    void approvalReChecksBalanceAndRejectsIfNoLongerSufficient() {
        Wallet wallet = newFundedWallet(new BigDecimal("150.00"));
        BankAccount accountA = verifiedAccount(wallet, "15");
        withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("10.00"), "USD", accountA.getId(), "risk-recheck-history-" + UUID.randomUUID());
        BankAccount accountB = verifiedAccount(wallet, "16");
        Withdrawal held = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("100.00"), "USD", accountB.getId(), "risk-recheck-held-" + UUID.randomUUID());
        assertEquals(WithdrawalStatus.PENDING_REVIEW, held.getStatus());

        // Drain the rest of the balance via a normal completed withdrawal
        // while the risky one is still sitting in review.
        Withdrawal drain = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("135.00"), "USD", accountA.getId(), "risk-recheck-drain-" + UUID.randomUUID());
        assertEquals(WithdrawalStatus.COMPLETED, drain.getStatus());

        Withdrawal approved = withdrawalService.approveReviewedWithdrawal(held.getId(), wallet.getId());

        assertEquals(WithdrawalStatus.FAILED, approved.getStatus());
        assertEquals("Insufficient available funds at the time of review approval", approved.getFailureReason());
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
            entityManager.createNativeQuery("DELETE FROM bank_accounts WHERE wallet_id IN (:ids)")
                    .setParameter("ids", testWalletIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM wallets WHERE id IN (:ids)")
                    .setParameter("ids", testWalletIds).executeUpdate();
        });
    }
}
