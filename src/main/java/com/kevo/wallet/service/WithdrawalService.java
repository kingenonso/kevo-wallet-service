package com.kevo.wallet.service;

import com.kevo.wallet.entity.*;
import com.kevo.wallet.payment.PaymentProvider;
import com.kevo.wallet.repository.LedgerEntryRepository;
import com.kevo.wallet.repository.WalletRepository;
import com.kevo.wallet.repository.WithdrawalRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

@Service
public class WithdrawalService {

    private final WithdrawalRepository withdrawalRepository;
    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final WalletService walletService;
    private final PaymentProvider paymentProvider;

    public WithdrawalService(WithdrawalRepository withdrawalRepository,
                              WalletRepository walletRepository,
                              LedgerEntryRepository ledgerEntryRepository,
                              WalletService walletService,
                              PaymentProvider paymentProvider) {
        this.withdrawalRepository = withdrawalRepository;
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.walletService = walletService;
        this.paymentProvider = paymentProvider;
    }

    @Transactional
    public Withdrawal createWithdrawal(Long walletId, BigDecimal amount, String currency,
                                        String destinationReference, String idempotencyKey) {

        var existing = withdrawalRepository.findByWalletIdAndIdempotencyKey(walletId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        Wallet wallet = walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));

        Map<Bucket, BigDecimal> balances = walletService.computeBalances(walletId);
        BigDecimal available = balances.getOrDefault(Bucket.AVAILABLE, BigDecimal.ZERO);

        if (available.compareTo(amount) < 0) {
            return saveOutcome(new Withdrawal(wallet.getId(), idempotencyKey, null,
                    paymentProvider.getProviderName(), amount, currency, destinationReference,
                    WithdrawalStatus.FAILED, "Insufficient available funds", null), walletId, idempotencyKey);
        }

        PaymentProvider.PaymentResult result = paymentProvider.processWithdrawal(amount, currency, idempotencyKey);

        if (!result.success()) {
            return saveOutcome(new Withdrawal(wallet.getId(), idempotencyKey, result.providerReference(),
                    paymentProvider.getProviderName(), amount, currency, destinationReference,
                    WithdrawalStatus.FAILED, result.failureReason(), null), walletId, idempotencyKey);
        }

        LedgerEntry entry = new LedgerEntry(
                wallet.getId(),
                result.providerReference(),
                Direction.DEBIT,
                Bucket.AVAILABLE,
                "WITHDRAWAL",
                amount,
                currency,
                "Withdrawal via " + paymentProvider.getProviderName(),
                null,
                null
        );
        entry = ledgerEntryRepository.save(entry);

        Withdrawal completed = new Withdrawal(wallet.getId(), idempotencyKey, result.providerReference(),
                paymentProvider.getProviderName(), amount, currency, destinationReference,
                WithdrawalStatus.COMPLETED, null, entry.getId());

        return saveOutcome(completed, walletId, idempotencyKey);
    }

    private Withdrawal saveOutcome(Withdrawal withdrawal, Long walletId, String idempotencyKey) {
        try {
            return withdrawalRepository.save(withdrawal);
        } catch (DataIntegrityViolationException e) {
            return withdrawalRepository.findByWalletIdAndIdempotencyKey(walletId, idempotencyKey)
                    .orElseThrow(() -> e);
        }
    }
}
