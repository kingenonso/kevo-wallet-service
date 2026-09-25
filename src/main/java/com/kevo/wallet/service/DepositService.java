package com.kevo.wallet.service;

import com.kevo.wallet.entity.Deposit;
import com.kevo.wallet.entity.DepositStatus;
import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.payment.PaymentProvider;
import com.kevo.wallet.repository.DepositRepository;
import com.kevo.wallet.repository.WalletRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
public class DepositService {

    private static final BigDecimal DAILY_DEPOSIT_LIMIT = new BigDecimal("10000.00");

    private final DepositRepository depositRepository;
    private final WalletRepository walletRepository;
    private final LedgerPostingService ledgerPostingService;
    private final PaymentProvider paymentProvider;

    public DepositService(DepositRepository depositRepository,
                           WalletRepository walletRepository,
                           LedgerPostingService ledgerPostingService,
                           PaymentProvider paymentProvider) {
        this.depositRepository = depositRepository;
        this.walletRepository = walletRepository;
        this.ledgerPostingService = ledgerPostingService;
        this.paymentProvider = paymentProvider;
    }

    @Transactional
    public Deposit createDeposit(Wallet wallet, BigDecimal amount, String currency, String idempotencyKey) {
        Optional<Deposit> existing = depositRepository.findByWalletIdAndIdempotencyKey(wallet.getId(), idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        if (!wallet.getCurrency().equalsIgnoreCase(currency)) {
            return saveOutcome(new Deposit(wallet.getId(), idempotencyKey, null,
                    paymentProvider.getProviderName(), amount, currency, DepositStatus.FAILED, null),
                    wallet.getId(), idempotencyKey);
        }

        walletRepository.findByIdForUpdate(wallet.getId())
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + wallet.getId()));

        BigDecimal alreadyDepositedToday = depositRepository.sumAmountByWalletIdAndStatusAndCreatedAtAfter(
                wallet.getId(), DepositStatus.COMPLETED, Instant.now().minus(24, ChronoUnit.HOURS));

        if (alreadyDepositedToday.add(amount).compareTo(DAILY_DEPOSIT_LIMIT) > 0) {
            return saveOutcome(new Deposit(wallet.getId(), idempotencyKey, null,
                    paymentProvider.getProviderName(), amount, currency, DepositStatus.FAILED, null),
                    wallet.getId(), idempotencyKey);
        }

        PaymentProvider.PaymentResult result = paymentProvider.processDeposit(amount, currency, idempotencyKey);

        if (!result.success()) {
            return saveOutcome(new Deposit(wallet.getId(), idempotencyKey, result.providerReference(),
                    paymentProvider.getProviderName(), amount, currency, DepositStatus.FAILED, null),
                    wallet.getId(), idempotencyKey);
        }

        try {
            return ledgerPostingService.postCompletedDeposit(
                    wallet, amount, currency, idempotencyKey, result.providerReference(), paymentProvider.getProviderName());
        } catch (DataIntegrityViolationException e) {
            return depositRepository.findByWalletIdAndIdempotencyKey(wallet.getId(), idempotencyKey)
                    .orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<Deposit> findExisting(Long walletId, String idempotencyKey) {
        return depositRepository.findByWalletIdAndIdempotencyKey(walletId, idempotencyKey);
    }

    private Deposit saveOutcome(Deposit deposit, Long walletId, String idempotencyKey) {
        try {
            return depositRepository.save(deposit);
        } catch (DataIntegrityViolationException e) {
            return depositRepository.findByWalletIdAndIdempotencyKey(walletId, idempotencyKey)
                    .orElseThrow(() -> e);
        }
    }
}
