package com.kevo.wallet.service;

import com.kevo.wallet.entity.*;
import com.kevo.wallet.repository.LedgerEntryRepository;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit coverage for ReconciliationService (spec Section 24). Confirms a
 * wallet that went through a normal sequence of real operations reconciles
 * completely clean, and that the specific kinds of corruption the spec asks
 * for (a missing paired ledger entry, mismatched amounts between a paired
 * entry, and an orphaned record with no ledger entry at all) are each
 * detected and reported - never silently fixed.
 */
@SpringBootTest
class ReconciliationServiceTest {

    @Autowired private WalletService walletService;
    @Autowired private DepositService depositService;
    @Autowired private WithdrawalService withdrawalService;
    @Autowired private BankAccountService bankAccountService;
    @Autowired private FundLockService fundLockService;
    @Autowired private DepositReversalService depositReversalService;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private static final AtomicLong idCounter = new AtomicLong();
    private final List<Long> testWalletIds = new ArrayList<>();

    private Wallet newWallet() {
        long kevoUserId = 940_000_000L + (System.currentTimeMillis() % 1_000_000) + idCounter.incrementAndGet();
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);
        testWalletIds.add(wallet.getId());
        return wallet;
    }

    @Test
    void aWalletThatWentThroughNormalOperationsReconcilesClean() {
        Wallet buyer = newWallet();
        Wallet seller = newWallet();
        depositService.createDeposit(buyer, new BigDecimal("1000.00"), "USD", "recon-clean-deposit-1");

        FundLock lock = fundLockService.createLock(buyer.getId(), new BigDecimal("300.00"), "USD", 555001L, "recon-clean-lock-1");
        assertEquals(FundLockStatus.ACTIVE, lock.getStatus());
        fundLockService.captureLock(buyer.getId(), 555001L, seller.getKevoUserId());

        fundLockService.createLock(buyer.getId(), new BigDecimal("100.00"), "USD", 555002L, "recon-clean-lock-2");
        fundLockService.releaseLock(buyer.getId(), 555002L);

        BankAccount account = bankAccountService.addBankAccount(buyer.getId(), "Recon Test", "Test Bank", "00001212343456");
        bankAccountService.verifyBankAccount(account.getId(), buyer.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "UPDATE bank_accounts SET verified_at = now() - interval '25 hours' WHERE id = :id")
                        .setParameter("id", account.getId())
                        .executeUpdate());
        withdrawalService.createWithdrawal(buyer.getId(), new BigDecimal("50.00"), "USD", account.getId(), "recon-clean-withdrawal-1");

        ReconciliationService.ReconciliationReport buyerReport = reconciliationService.reconcileWallet(buyer.getId());
        ReconciliationService.ReconciliationReport sellerReport = reconciliationService.reconcileWallet(seller.getId());

        assertTrue(buyerReport.clean(), "Buyer wallet should reconcile clean, found: " + buyerReport.findings());
        assertTrue(sellerReport.clean(), "Seller wallet should reconcile clean, found: " + sellerReport.findings());
    }

    @Test
    void aDepositAndItsReversalTogetherReconcileClean() {
        Wallet wallet = newWallet();
        depositService.createDeposit(wallet, new BigDecimal("200.00"), "USD", "recon-reversal-deposit-1");
        Deposit deposit = depositService.findExisting(wallet.getId(), "recon-reversal-deposit-1").orElseThrow();
        depositReversalService.reverseDeposit(wallet.getKevoUserId(), deposit.getId(), "recon test reversal");

        ReconciliationService.ReconciliationReport report = reconciliationService.reconcileWallet(wallet.getId());

        assertTrue(report.clean(), "Deposit + reversal should reconcile clean, found: " + report.findings());
    }

    @Test
    void aFundLockMissingItsSecondLedgerEntryIsDetected() {
        Wallet buyer = newWallet();
        depositService.createDeposit(buyer, new BigDecimal("500.00"), "USD", "recon-missing-deposit-1");
        FundLock lock = fundLockService.createLock(buyer.getId(), new BigDecimal("100.00"), "USD", 555003L, "recon-missing-lock-1");

        // Simulate corruption: delete the DEBIT side of the lock (the one NOT
        // referenced by fund_locks.lock_ledger_entry_id, so the delete itself
        // doesn't violate that foreign key), leaving only the CREDIT side -
        // this should never happen through the real code path, which is
        // exactly why reconciliation needs to be able to catch it.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "DELETE FROM ledger_entries WHERE wallet_id = :walletId AND entry_type = 'LOCK' AND id <> :keepId")
                        .setParameter("walletId", buyer.getId())
                        .setParameter("keepId", lock.getLockLedgerEntryId())
                        .executeUpdate());

        ReconciliationService.ReconciliationReport report = reconciliationService.reconcileWallet(buyer.getId());

        assertFalse(report.clean());
        assertTrue(report.findings().stream().anyMatch(f -> f.category().equals("MISSING_LEDGER_ENTRY")),
                "Expected a MISSING_LEDGER_ENTRY finding, got: " + report.findings());
    }

    @Test
    void aPairedLedgerEntryWithMismatchedAmountsIsDetected() {
        Wallet buyer = newWallet();
        depositService.createDeposit(buyer, new BigDecimal("500.00"), "USD", "recon-mismatch-deposit-1");
        FundLock lock = fundLockService.createLock(buyer.getId(), new BigDecimal("100.00"), "USD", 555004L, "recon-mismatch-lock-1");

        // Simulate corruption: silently change one side's amount so the pair no
        // longer nets to zero.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery("UPDATE ledger_entries SET amount = 999.00 WHERE id = :id")
                        .setParameter("id", lock.getLockLedgerEntryId())
                        .executeUpdate());

        ReconciliationService.ReconciliationReport report = reconciliationService.reconcileWallet(buyer.getId());

        assertFalse(report.clean());
        assertTrue(report.findings().stream().anyMatch(f -> f.category().equals("BALANCE_INCONSISTENCY")),
                "Expected a BALANCE_INCONSISTENCY finding, got: " + report.findings());
    }

    @Test
    void aCompletedDepositWithNoLedgerEntryIsDetectedAsOrphaned() {
        Wallet wallet = newWallet();
        depositService.createDeposit(wallet, new BigDecimal("150.00"), "USD", "recon-orphan-deposit-1");
        Deposit deposit = depositService.findExisting(wallet.getId(), "recon-orphan-deposit-1").orElseThrow();

        // Simulate corruption: the deposit record itself loses its link to the
        // ledger entry that should back it up.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery("UPDATE deposits SET ledger_entry_id = NULL WHERE id = :id")
                        .setParameter("id", deposit.getId())
                        .executeUpdate());

        ReconciliationService.ReconciliationReport report = reconciliationService.reconcileWallet(wallet.getId());

        assertFalse(report.clean());
        assertTrue(report.findings().stream().anyMatch(f -> f.category().equals("ORPHANED_RECORD")),
                "Expected an ORPHANED_RECORD finding, got: " + report.findings());
    }

    @Test
    void reconcileAllWalletsOnlyReturnsWalletsWithFindings() {
        Wallet cleanWallet = newWallet();
        depositService.createDeposit(cleanWallet, new BigDecimal("100.00"), "USD", "recon-all-clean-deposit-1");

        Wallet dirtyWallet = newWallet();
        depositService.createDeposit(dirtyWallet, new BigDecimal("100.00"), "USD", "recon-all-dirty-deposit-1");
        Deposit dirtyDeposit = depositService.findExisting(dirtyWallet.getId(), "recon-all-dirty-deposit-1").orElseThrow();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery("UPDATE deposits SET ledger_entry_id = NULL WHERE id = :id")
                        .setParameter("id", dirtyDeposit.getId())
                        .executeUpdate());

        List<ReconciliationService.ReconciliationReport> dirtyReports = reconciliationService.reconcileAllWallets();

        assertTrue(dirtyReports.stream().anyMatch(r -> r.walletId().equals(dirtyWallet.getId())));
        assertTrue(dirtyReports.stream().noneMatch(r -> r.walletId().equals(cleanWallet.getId())));
    }

    @AfterEach
    void cleanup() {
        if (testWalletIds.isEmpty()) return;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM withdrawals WHERE wallet_id IN (:ids)")
                    .setParameter("ids", testWalletIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM deposits WHERE wallet_id IN (:ids)")
                    .setParameter("ids", testWalletIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM fund_locks WHERE wallet_id IN (:ids)")
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
