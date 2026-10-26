package com.kevo.wallet.service;

import com.kevo.wallet.entity.*;
import com.kevo.wallet.repository.DepositRepository;
import com.kevo.wallet.repository.LedgerEntryRepository;
import com.kevo.wallet.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Handles a previously COMPLETED deposit later being reversed, refunded,
 * charged back, disputed, or cancelled by the payment provider (spec Section
 * 17). The original deposit and its ledger entry are never edited or
 * deleted - a compensating DEBIT ledger entry is recorded instead, and the
 * deposit is marked REVERSED. If the funds have already been spent or
 * withdrawn elsewhere, reversing the deposit can push the wallet's available
 * balance negative; when that happens the wallet is restricted from further
 * withdrawals until the resulting owed balance is resolved, rather than
 * letting more money leave a wallet that already owes KEVO money back.
 *
 * In production this would be triggered by a payment-provider webhook (see
 * the upcoming WebhookEventService); for the demo prototype it's triggered
 * directly through DepositReversalController, simulating that same event.
 */
@Service
public class DepositReversalService {

    private final DepositRepository depositRepository;
    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final WalletService walletService;

    public DepositReversalService(DepositRepository depositRepository,
                                   WalletRepository walletRepository,
                                   LedgerEntryRepository ledgerEntryRepository,
                                   WalletService walletService) {
        this.depositRepository = depositRepository;
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.walletService = walletService;
    }

    @Transactional
    public Deposit reverseDeposit(Long kevoUserId, Long depositId, String reason) {
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);

        Deposit deposit = depositRepository.findById(depositId)
                .orElseThrow(() -> new IllegalArgumentException("Deposit not found"));

        if (!deposit.getWalletId().equals(wallet.getId())) {
            throw new IllegalArgumentException("Deposit not found for this wallet");
        }

        if (deposit.getStatus() == DepositStatus.REVERSED) {
            // Already reversed - idempotent no-op rather than reversing twice.
            return deposit;
        }
        if (deposit.getStatus() != DepositStatus.COMPLETED) {
            throw new IllegalStateException("Only a COMPLETED deposit can be reversed (was " + deposit.getStatus() + ")");
        }
        if (deposit.getLedgerEntryId() == null) {
            throw new IllegalStateException("Deposit has no ledger entry to reverse");
        }

        // Lock the wallet row so a concurrent withdrawal or another reversal
        // can't race against this one (same pattern as withdrawals/deposits).
        Wallet lockedWallet = walletRepository.findByIdForUpdate(wallet.getId())
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + wallet.getId()));

        LedgerEntry originalEntry = ledgerEntryRepository.findById(deposit.getLedgerEntryId())
                .orElseThrow(() -> new IllegalStateException("Original ledger entry not found: " + deposit.getLedgerEntryId()));

        LedgerEntry reversalEntry = new LedgerEntry(
                lockedWallet.getId(),
                originalEntry.getLedgerTransactionId(),
                Direction.DEBIT,
                originalEntry.getBucket(),
                "DEPOSIT_REVERSAL",
                originalEntry.getAmount(),
                originalEntry.getCurrency(),
                "Reversal of deposit " + deposit.getId() + ": " + reason,
                null,
                originalEntry.getId()
        );
        ledgerEntryRepository.save(reversalEntry);

        deposit.markReversed(reversalEntry.getId());
        depositRepository.save(deposit);

        BigDecimal available = walletService.computeBalances(lockedWallet.getId())
                .getOrDefault(Bucket.AVAILABLE, BigDecimal.ZERO);
        if (available.signum() < 0 && lockedWallet.getStatus() == WalletStatus.ACTIVE) {
            lockedWallet.restrict();
            walletRepository.save(lockedWallet);
        }

        return deposit;
    }

    /**
     * The inverse of reverseDeposit() - handles a previously REVERSED
     * deposit having its funds reinstated (a Stripe dispute resolved in
     * KEVO's favor, charge.dispute.funds_reinstated). The reversal's own
     * DEBIT ledger entry is never edited or deleted, same discipline as
     * reverseDeposit() itself - a compensating CREDIT entry is recorded
     * instead, referencing that reversal entry directly via
     * Deposit.reversalLedgerEntryId, and the deposit goes back to
     * COMPLETED. If this was the reversal that pushed the wallet
     * RESTRICTED and the wallet is solvent again, the restriction is
     * lifted - the exact mirror of reverseDeposit() imposing it.
     */
    @Transactional
    public Deposit reinstateDeposit(Long kevoUserId, Long depositId, String reason) {
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);

        Deposit deposit = depositRepository.findById(depositId)
                .orElseThrow(() -> new IllegalArgumentException("Deposit not found"));

        if (!deposit.getWalletId().equals(wallet.getId())) {
            throw new IllegalArgumentException("Deposit not found for this wallet");
        }

        if (deposit.getStatus() == DepositStatus.COMPLETED) {
            // Already reinstated (or was never actually reversed) -
            // idempotent no-op rather than crediting twice.
            return deposit;
        }
        if (deposit.getStatus() != DepositStatus.REVERSED) {
            throw new IllegalStateException("Only a REVERSED deposit can be reinstated (was " + deposit.getStatus() + ")");
        }
        if (deposit.getReversalLedgerEntryId() == null) {
            throw new IllegalStateException("Deposit has no reversal ledger entry to reinstate against");
        }

        // Lock the wallet row for the same reason reverseDeposit() does -
        // a concurrent withdrawal or another reversal/reinstatement can't
        // race against this one.
        Wallet lockedWallet = walletRepository.findByIdForUpdate(wallet.getId())
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + wallet.getId()));

        LedgerEntry reversalEntry = ledgerEntryRepository.findById(deposit.getReversalLedgerEntryId())
                .orElseThrow(() -> new IllegalStateException("Reversal ledger entry not found: " + deposit.getReversalLedgerEntryId()));

        LedgerEntry reinstatementEntry = new LedgerEntry(
                lockedWallet.getId(),
                reversalEntry.getLedgerTransactionId(),
                Direction.CREDIT,
                reversalEntry.getBucket(),
                "DEPOSIT_REVERSAL_REINSTATED",
                reversalEntry.getAmount(),
                reversalEntry.getCurrency(),
                "Reinstatement of deposit " + deposit.getId() + " (dispute resolved in KEVO's favor): " + reason,
                null,
                reversalEntry.getId()
        );
        ledgerEntryRepository.save(reinstatementEntry);

        // Same status/ledger-entry shape as a deposit completing for the
        // first time - see Deposit.markCompleted()'s own docstring for why
        // this deliberately reuses it rather than adding a near-duplicate.
        deposit.markCompleted(reinstatementEntry.getId());
        depositRepository.save(deposit);

        BigDecimal available = walletService.computeBalances(lockedWallet.getId())
                .getOrDefault(Bucket.AVAILABLE, BigDecimal.ZERO);
        if (available.signum() >= 0 && lockedWallet.getStatus() == WalletStatus.RESTRICTED) {
            lockedWallet.liftRestriction();
            walletRepository.save(lockedWallet);
        }

        return deposit;
    }
}
