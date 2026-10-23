package com.kevo.wallet.service;

import com.kevo.wallet.entity.*;
import com.kevo.wallet.repository.DepositRepository;
import com.kevo.wallet.repository.LedgerEntryRepository;
import com.kevo.wallet.repository.WithdrawalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class LedgerPostingService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final DepositRepository depositRepository;
    private final WithdrawalRepository withdrawalRepository;

    public LedgerPostingService(LedgerEntryRepository ledgerEntryRepository, DepositRepository depositRepository,
                                 WithdrawalRepository withdrawalRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.depositRepository = depositRepository;
        this.withdrawalRepository = withdrawalRepository;
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

    // Finalizes a real withdrawal once Stripe's payout.paid webhook confirms
    // the money actually left for the user's bank. The funds were already
    // moved out of AVAILABLE into WITHDRAWAL_PENDING when the payout was
    // first submitted (see RealWithdrawalService) - this just debits them
    // out of WITHDRAWAL_PENDING for good. There is no corresponding credit:
    // the money is gone, paid out to a bank account outside KEVO's books.
    @Transactional
    public Withdrawal completePendingWithdrawal(Withdrawal withdrawal) {
        LedgerEntry debitPending = new LedgerEntry(
                withdrawal.getWalletId(),
                withdrawal.getProviderReference(),
                Direction.DEBIT,
                Bucket.WITHDRAWAL_PENDING,
                "WITHDRAWAL_SETTLED",
                withdrawal.getAmount(),
                withdrawal.getCurrency(),
                "Withdrawal via " + withdrawal.getProviderName() + " paid out",
                null,
                null
        );
        debitPending = ledgerEntryRepository.save(debitPending);
        withdrawal.markCompletedAfterPending(debitPending.getId());
        return withdrawalRepository.save(withdrawal);
    }

    // Finalizes a real withdrawal once Stripe's payout.failed webhook
    // reports the payout never reached the user's bank after all (e.g. the
    // external account was closed). The reserved funds are returned to
    // AVAILABLE so the user isn't left permanently short.
    @Transactional
    public Withdrawal failPendingWithdrawal(Withdrawal withdrawal, String reason) {
        String ledgerTransactionId = UUID.randomUUID().toString();

        LedgerEntry debitPending = new LedgerEntry(
                withdrawal.getWalletId(),
                ledgerTransactionId,
                Direction.DEBIT,
                Bucket.WITHDRAWAL_PENDING,
                "WITHDRAWAL_FAILED",
                withdrawal.getAmount(),
                withdrawal.getCurrency(),
                "Withdrawal via " + withdrawal.getProviderName() + " failed after submission: " + reason,
                null,
                null
        );
        ledgerEntryRepository.save(debitPending);

        LedgerEntry creditAvailable = new LedgerEntry(
                withdrawal.getWalletId(),
                ledgerTransactionId,
                Direction.CREDIT,
                Bucket.AVAILABLE,
                "WITHDRAWAL_FAILED",
                withdrawal.getAmount(),
                withdrawal.getCurrency(),
                "Withdrawal via " + withdrawal.getProviderName() + " failed after submission: " + reason,
                null,
                null
        );
        ledgerEntryRepository.save(creditAvailable);

        withdrawal.markFailedAfterPending(reason);
        return withdrawalRepository.save(withdrawal);
    }
}
