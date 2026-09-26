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

        deposit.markReversed();
        depositRepository.save(deposit);

        BigDecimal available = walletService.computeBalances(lockedWallet.getId())
                .getOrDefault(Bucket.AVAILABLE, BigDecimal.ZERO);
        if (available.signum() < 0 && lockedWallet.getStatus() == WalletStatus.ACTIVE) {
            lockedWallet.restrict();
            walletRepository.save(lockedWallet);
        }

        return deposit;
    }
}
