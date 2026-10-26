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
 * JUnit coverage for DepositReversalService (spec Section 17: payment
 * reversals / chargebacks). Confirms the original deposit and ledger entry
 * are never edited, a compensating entry is created instead, a reversal that
 * pushes available funds negative restricts the wallet from further
 * withdrawals, reversal is idempotent, and cross-wallet reversal attempts
 * are rejected.
 */
@SpringBootTest
class DepositReversalTest {

    @Autowired private WalletService walletService;
    @Autowired private DepositService depositService;
    @Autowired private BankAccountService bankAccountService;
    @Autowired private WithdrawalService withdrawalService;
    @Autowired private DepositReversalService depositReversalService;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private static final AtomicLong idCounter = new AtomicLong();
    private final List<Long> testWalletIds = new ArrayList<>();

    private Wallet newFundedWallet(BigDecimal depositAmount, String depositKey) {
        long kevoUserId = 930_000_000L + (System.currentTimeMillis() % 1_000_000) + idCounter.incrementAndGet();
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);
        testWalletIds.add(wallet.getId());
        depositService.createDeposit(wallet, depositAmount, "USD", depositKey);
        return wallet;
    }

    @Test
    void reversingACompletedDepositDebitsAvailableAndMarksItReversed() {
        Wallet wallet = newFundedWallet(new BigDecimal("500.00"), "reversal-basic-1");
        Deposit deposit = depositService.findExisting(wallet.getId(), "reversal-basic-1").orElseThrow();

        Deposit reversed = depositReversalService.reverseDeposit(wallet.getKevoUserId(), deposit.getId(), "chargeback test");

        assertEquals(DepositStatus.REVERSED, reversed.getStatus());
        BigDecimal available = walletService.computeBalances(wallet.getId()).get(Bucket.AVAILABLE);
        assertEquals(0, new BigDecimal("0.00").compareTo(available));

        List<LedgerEntry> entries = ledgerEntryRepository.findByWalletId(wallet.getId());
        assertEquals(2, entries.size());
        LedgerEntry original = entries.stream().filter(e -> e.getReversesEntryId() == null).findFirst().orElseThrow();
        LedgerEntry reversal = entries.stream().filter(e -> e.getReversesEntryId() != null).findFirst().orElseThrow();
        assertEquals(Direction.CREDIT, original.getDirection());
        assertEquals(Direction.DEBIT, reversal.getDirection());
        assertEquals(original.getId(), reversal.getReversesEntryId());
        assertEquals(0, original.getAmount().compareTo(reversal.getAmount()));
    }

    @Test
    void reversingADepositWhoseFundsAreAlreadyWithdrawnRestrictsTheWallet() {
        Wallet wallet = newFundedWallet(new BigDecimal("500.00"), "reversal-restrict-1");
        Deposit deposit = depositService.findExisting(wallet.getId(), "reversal-restrict-1").orElseThrow();

        BankAccount account = bankAccountService.addBankAccount(wallet.getId(), "Reversal Test", "Test Bank", "00005555666677");
        bankAccountService.verifyBankAccount(account.getId(), wallet.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "UPDATE bank_accounts SET verified_at = now() - interval '25 hours' WHERE id = :id")
                        .setParameter("id", account.getId())
                        .executeUpdate());

        Withdrawal withdrawal = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("400.00"), "USD", account.getId(), "reversal-restrict-withdrawal-1");
        assertEquals(WithdrawalStatus.COMPLETED, withdrawal.getStatus());

        depositReversalService.reverseDeposit(wallet.getKevoUserId(), deposit.getId(), "chargeback after withdrawal");

        BigDecimal available = walletService.computeBalances(wallet.getId()).get(Bucket.AVAILABLE);
        assertEquals(0, new BigDecimal("-400.00").compareTo(available));

        Wallet reloaded = walletService.getOrCreateWallet(wallet.getKevoUserId());
        assertEquals(WalletStatus.RESTRICTED, reloaded.getStatus());

        Withdrawal blocked = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("1.00"), "USD", account.getId(), "reversal-restrict-withdrawal-2");
        assertEquals(WithdrawalStatus.FAILED, blocked.getStatus());
        assertEquals("Wallet is restricted from withdrawals", blocked.getFailureReason());
    }

    @Test
    void reversingTheSameDepositTwiceIsIdempotent() {
        Wallet wallet = newFundedWallet(new BigDecimal("500.00"), "reversal-idempotent-1");
        Deposit deposit = depositService.findExisting(wallet.getId(), "reversal-idempotent-1").orElseThrow();

        depositReversalService.reverseDeposit(wallet.getKevoUserId(), deposit.getId(), "first call");
        depositReversalService.reverseDeposit(wallet.getKevoUserId(), deposit.getId(), "second call");

        List<LedgerEntry> entries = ledgerEntryRepository.findByWalletId(wallet.getId());
        assertEquals(2, entries.size(), "A second reversal of the same deposit must not create a second compensating entry");
    }

    @Test
    void reversingADepositOwnedByAnotherWalletFails() {
        Wallet owner = newFundedWallet(new BigDecimal("500.00"), "reversal-idor-owner-1");
        Wallet attacker = newFundedWallet(new BigDecimal("500.00"), "reversal-idor-attacker-1");
        Deposit ownerDeposit = depositService.findExisting(owner.getId(), "reversal-idor-owner-1").orElseThrow();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                depositReversalService.reverseDeposit(attacker.getKevoUserId(), ownerDeposit.getId(), "not yours"));
        assertEquals("Deposit not found for this wallet", ex.getMessage());
    }

    @Test
    void reversingANonexistentDepositFails() {
        Wallet wallet = newFundedWallet(new BigDecimal("100.00"), "reversal-missing-1");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                depositReversalService.reverseDeposit(wallet.getKevoUserId(), 999_999_999L, "nope"));
        assertEquals("Deposit not found", ex.getMessage());
    }

    // --- reinstateDeposit(): the inverse, for a Stripe dispute resolved in
    // KEVO's favor (charge.dispute.funds_reinstated) ---

    @Test
    void reinstatingAReversedDepositCreditsAvailableAndMarksItCompleted() {
        Wallet wallet = newFundedWallet(new BigDecimal("500.00"), "reinstate-basic-1");
        Deposit deposit = depositService.findExisting(wallet.getId(), "reinstate-basic-1").orElseThrow();

        depositReversalService.reverseDeposit(wallet.getKevoUserId(), deposit.getId(), "chargeback test");
        Deposit reinstated = depositReversalService.reinstateDeposit(wallet.getKevoUserId(), deposit.getId(), "dispute won");

        assertEquals(DepositStatus.COMPLETED, reinstated.getStatus());
        BigDecimal available = walletService.computeBalances(wallet.getId()).get(Bucket.AVAILABLE);
        assertEquals(0, new BigDecimal("500.00").compareTo(available));

        List<LedgerEntry> entries = ledgerEntryRepository.findByWalletId(wallet.getId());
        assertEquals(3, entries.size(), "original CREDIT, reversal DEBIT, and reinstatement CREDIT");
        LedgerEntry reversal = entries.stream().filter(e -> "DEPOSIT_REVERSAL".equals(e.getEntryType())).findFirst().orElseThrow();
        LedgerEntry reinstatement = entries.stream().filter(e -> "DEPOSIT_REVERSAL_REINSTATED".equals(e.getEntryType())).findFirst().orElseThrow();
        assertEquals(Direction.CREDIT, reinstatement.getDirection());
        assertEquals(reversal.getId(), reinstatement.getReversesEntryId(), "reinstatement must reference the specific reversal entry it undoes, not the original");
        assertEquals(0, reversal.getAmount().compareTo(reinstatement.getAmount()));
    }

    @Test
    void reinstatingADepositThatPushedTheWalletNegativeLiftsTheRestriction() {
        Wallet wallet = newFundedWallet(new BigDecimal("500.00"), "reinstate-restrict-1");
        Deposit deposit = depositService.findExisting(wallet.getId(), "reinstate-restrict-1").orElseThrow();

        BankAccount account = bankAccountService.addBankAccount(wallet.getId(), "Reinstate Test", "Test Bank", "00009999888877");
        bankAccountService.verifyBankAccount(account.getId(), wallet.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "UPDATE bank_accounts SET verified_at = now() - interval '25 hours' WHERE id = :id")
                        .setParameter("id", account.getId())
                        .executeUpdate());

        withdrawalService.createWithdrawal(wallet.getId(), new BigDecimal("400.00"), "USD", account.getId(), "reinstate-restrict-withdrawal-1");
        depositReversalService.reverseDeposit(wallet.getKevoUserId(), deposit.getId(), "chargeback after withdrawal");

        Wallet restricted = walletService.getOrCreateWallet(wallet.getKevoUserId());
        assertEquals(WalletStatus.RESTRICTED, restricted.getStatus());

        depositReversalService.reinstateDeposit(wallet.getKevoUserId(), deposit.getId(), "dispute won after all");

        BigDecimal available = walletService.computeBalances(wallet.getId()).get(Bucket.AVAILABLE);
        assertEquals(0, new BigDecimal("100.00").compareTo(available), "500 deposited - 400 withdrawn - 500 reversed + 500 reinstated == 100");

        Wallet reloaded = walletService.getOrCreateWallet(wallet.getKevoUserId());
        assertEquals(WalletStatus.ACTIVE, reloaded.getStatus(), "solvent again - restriction should be lifted");

        Withdrawal allowedAgain = withdrawalService.createWithdrawal(
                wallet.getId(), new BigDecimal("1.00"), "USD", account.getId(), "reinstate-restrict-withdrawal-2");
        assertEquals(WithdrawalStatus.COMPLETED, allowedAgain.getStatus(), "no longer restricted, so a real withdrawal should go through");
    }

    @Test
    void reinstatingTheSameDepositTwiceIsIdempotent() {
        Wallet wallet = newFundedWallet(new BigDecimal("500.00"), "reinstate-idempotent-1");
        Deposit deposit = depositService.findExisting(wallet.getId(), "reinstate-idempotent-1").orElseThrow();

        depositReversalService.reverseDeposit(wallet.getKevoUserId(), deposit.getId(), "chargeback");
        depositReversalService.reinstateDeposit(wallet.getKevoUserId(), deposit.getId(), "first reinstate call");
        depositReversalService.reinstateDeposit(wallet.getKevoUserId(), deposit.getId(), "second reinstate call");

        List<LedgerEntry> entries = ledgerEntryRepository.findByWalletId(wallet.getId());
        assertEquals(3, entries.size(), "A second reinstatement of the same deposit must not create a second compensating entry");
    }

    @Test
    void reinstatingADepositThatWasNeverReversedIsIdempotentNotAnError() {
        Wallet wallet = newFundedWallet(new BigDecimal("500.00"), "reinstate-never-reversed-1");
        Deposit deposit = depositService.findExisting(wallet.getId(), "reinstate-never-reversed-1").orElseThrow();

        Deposit result = depositReversalService.reinstateDeposit(wallet.getKevoUserId(), deposit.getId(), "no-op");

        assertEquals(DepositStatus.COMPLETED, result.getStatus());
        List<LedgerEntry> entries = ledgerEntryRepository.findByWalletId(wallet.getId());
        assertEquals(1, entries.size(), "a deposit that was never reversed must not get a spurious reinstatement entry");
    }

    @Test
    void reinstatingAStillPendingOrFailedDepositFails() {
        Wallet wallet = newFundedWallet(new BigDecimal("100.00"), "reinstate-wrong-state-1");
        // A failed deposit (currency mismatch) has no ledger entry at all -
        // neither COMPLETED nor REVERSED, so reinstating it is a real error,
        // not a state reinstateDeposit should silently accept.
        depositService.createDeposit(wallet, new BigDecimal("10.00"), "EUR", "reinstate-wrong-state-failed-1");
        Deposit failedDeposit = depositService.findExisting(wallet.getId(), "reinstate-wrong-state-failed-1").orElseThrow();
        assertEquals(DepositStatus.FAILED, failedDeposit.getStatus());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                depositReversalService.reinstateDeposit(wallet.getKevoUserId(), failedDeposit.getId(), "should not work"));
        assertTrue(ex.getMessage().contains("Only a REVERSED deposit can be reinstated"));
    }

    @Test
    void reinstatingADepositOwnedByAnotherWalletFails() {
        Wallet owner = newFundedWallet(new BigDecimal("500.00"), "reinstate-idor-owner-1");
        Wallet attacker = newFundedWallet(new BigDecimal("500.00"), "reinstate-idor-attacker-1");
        Deposit ownerDeposit = depositService.findExisting(owner.getId(), "reinstate-idor-owner-1").orElseThrow();
        depositReversalService.reverseDeposit(owner.getKevoUserId(), ownerDeposit.getId(), "setup");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                depositReversalService.reinstateDeposit(attacker.getKevoUserId(), ownerDeposit.getId(), "not yours"));
        assertEquals("Deposit not found for this wallet", ex.getMessage());
    }

    @AfterEach
    void cleanup() {
        if (testWalletIds.isEmpty()) return;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM withdrawals WHERE wallet_id IN (:ids)")
                    .setParameter("ids", testWalletIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM deposits WHERE wallet_id IN (:ids)")
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
