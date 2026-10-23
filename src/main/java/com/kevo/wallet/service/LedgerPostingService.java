package com.kevo.wallet.service;

import com.kevo.wallet.entity.*;
import com.kevo.wallet.repository.DepositRepository;
import com.kevo.wallet.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class LedgerPostingService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final DepositRepository depositRepository;

    public LedgerPostingService(LedgerEntryRepository ledgerEntryRepository, DepositRepository depositRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.depositRepository = depositRepository;
    }

    @Transactional
    public Deposit postCompletedDeposit(Wallet wallet, BigDecimal amount, String currency,
                                         String idempotencyKey, String providerReference, String providerName) {
        LedgerEntry entry = new LedgerEntry(
                wallet.getId(),
                providerReference,
                Direction.CREDIT,
                Bucket.AVAILABLE,
                "DEPOSIT",
                amount,
                currency,
                "Wallet funding via " + providerName,
                null,
                null
        );
        entry = ledgerEntryRepository.save(entry);

        Deposit deposit = new Deposit(wallet.getId(), idempotencyKey, providerReference,
                providerName, amount, currency, DepositStatus.COMPLETED, entry.getId());
        return depositRepository.save(deposit);
    }

    // Completes a deposit that was already recorded as PENDING when it was
    // first started (the real/Stripe flow) rather than creating a brand new
    // Deposit row the way postCompletedDeposit does for the synchronous demo
    // flow. Called only after a webhook has verified the money really cleared.
    @Transactional
    public Deposit completePendingDeposit(Deposit deposit) {
        LedgerEntry entry = new LedgerEntry(
                deposit.getWalletId(),
                deposit.getProviderReference(),
                Direction.CREDIT,
                Bucket.AVAILABLE,
                "DEPOSIT",
                deposit.getAmount(),
                deposit.getCurrency(),
                "Wallet funding via " + deposit.getProviderName(),
                null,
                null
        );
        entry = ledgerEntryRepository.save(entry);

        deposit.markCompleted(entry.getId());
        return depositRepository.save(deposit);
    }

    // No ledger entry gets created here - a failed deposit never touches the balance.
    @Transactional
    public Deposit failPendingDeposit(Deposit deposit) {
        deposit.markFailed();
        return depositRepository.save(deposit);
    }
}
