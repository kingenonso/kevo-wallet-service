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
}
