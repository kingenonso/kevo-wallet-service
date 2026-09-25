package com.kevo.wallet.service;

import com.kevo.wallet.entity.*;
import com.kevo.wallet.payment.PaymentProvider;
import com.kevo.wallet.repository.BankAccountRepository;
import com.kevo.wallet.repository.LedgerEntryRepository;
import com.kevo.wallet.repository.WalletRepository;
import com.kevo.wallet.repository.WithdrawalRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Service
public class WithdrawalService {

    private static final BigDecimal DAILY_WITHDRAWAL_LIMIT = new BigDecimal("5000.00");
    private static final long COOLING_OFF_HOURS = 24;

    private final WithdrawalRepository withdrawalRepository;
    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final BankAccountRepository bankAccountRepository;
    private final WalletService walletService;
    private final PaymentProvider paymentProvider;

    public WithdrawalService(WithdrawalRepository withdrawalRepository,
                              WalletRepository walletRepository,
                              LedgerEntryRepository ledgerEntryRepository,
                              BankAccountRepository bankAccountRepository,
                              WalletService walletService,
                              PaymentProvider paymentProvider) {
        this.withdrawalRepository = withdrawalRepository;
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.walletService = walletService;
        this.paymentProvider = paymentProvider;
    }

    @Transactional
    public Withdrawal createWithdrawal(Long walletId, BigDecimal amount, String currency,
                                        Long bankAccountId, String idempotencyKey) {

        var existing = withdrawalRepository.findByWalletIdAndIdempotencyKey(walletId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        Optional<BankAccount> accountOpt = bankAccountRepository.findById(bankAccountId);
        if (accountOpt.isEmpty()) {
            return saveOutcome(new Withdrawal(walletId, idempotencyKey, null,
                    paymentProvider.getProviderName(), amount, currency, null, "Invalid bank account",
                    WithdrawalStatus.FAILED, "Bank account not found", null), walletId, idempotencyKey);
        }

        BankAccount account = accountOpt.get();
        if (!account.getWalletId().equals(walletId)) {
            return saveOutcome(new Withdrawal(walletId, idempotencyKey, null,
                    paymentProvider.getProviderName(), amount, currency, account.getId(), "Invalid bank account",
                    WithdrawalStatus.FAILED, "Bank account does not belong to this wallet", null), walletId, idempotencyKey);
        }

        if (account.getStatus() != BankAccountStatus.VERIFIED) {
            return saveOutcome(new Withdrawal(walletId, idempotencyKey, null,
                    paymentProvider.getProviderName(), amount, currency, account.getId(), "Bank account is not verified",
                    WithdrawalStatus.FAILED, "Bank account is not verified", null), walletId, idempotencyKey);
        }

        String destinationReference = account.getBankName() + " ****" + account.getLastFour();

        if (account.getVerifiedAt() != null
                && account.getVerifiedAt().isAfter(LocalDateTime.now().minusHours(COOLING_OFF_HOURS))) {
            return saveOutcome(new Withdrawal(walletId, idempotencyKey, null,
                    paymentProvider.getProviderName(), amount, currency, account.getId(), destinationReference,
                    WithdrawalStatus.FAILED, "Bank account is in its cooling-off period", null), walletId, idempotencyKey);
        }

        Wallet wallet = walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));

        Map<Bucket, BigDecimal> balances = walletService.computeBalances(walletId);
        BigDecimal available = balances.getOrDefault(Bucket.AVAILABLE, BigDecimal.ZERO);

        if (available.compareTo(amount) < 0) {
            return saveOutcome(new Withdrawal(wallet.getId(), idempotencyKey, null,
                    paymentProvider.getProviderName(), amount, currency, account.getId(), destinationReference,
                    WithdrawalStatus.FAILED, "Insufficient available funds", null), walletId, idempotencyKey);
        }

        BigDecimal alreadyWithdrawnToday = withdrawalRepository.sumAmountByWalletIdAndStatusAndCreatedAtAfter(
                walletId, WithdrawalStatus.COMPLETED, LocalDateTime.now().minusHours(24));

        if (alreadyWithdrawnToday.add(amount).compareTo(DAILY_WITHDRAWAL_LIMIT) > 0) {
            return saveOutcome(new Withdrawal(wallet.getId(), idempotencyKey, null,
                    paymentProvider.getProviderName(), amount, currency, account.getId(), destinationReference,
                    WithdrawalStatus.FAILED, "Daily withdrawal limit exceeded", null), walletId, idempotencyKey);
        }

        PaymentProvider.PaymentResult result = paymentProvider.processWithdrawal(amount, currency, idempotencyKey);

        if (!result.success()) {
            return saveOutcome(new Withdrawal(wallet.getId(), idempotencyKey, result.providerReference(),
                    paymentProvider.getProviderName(), amount, currency, account.getId(), destinationReference,
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
                paymentProvider.getProviderName(), amount, currency, account.getId(), destinationReference,
                WithdrawalStatus.COMPLETED, null, entry.getId());

        return saveOutcome(completed, walletId, idempotencyKey);
    }

    // Called only as a fallback, from a DIFFERENT bean (the controller), when
    // createWithdrawal above throws. A failed insert can leave the transaction
    // that hit it unusable for anything further - including the friendly
    // "you already did this" re-lookup the code originally tried to do inline.
    // Being invoked externally through Spring's proxy guarantees this method
    // gets a brand new transaction and a clean session to work with.
    @Transactional(readOnly = true)
    public Optional<Withdrawal> findExisting(Long walletId, String idempotencyKey) {
        return withdrawalRepository.findByWalletIdAndIdempotencyKey(walletId, idempotencyKey);
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
