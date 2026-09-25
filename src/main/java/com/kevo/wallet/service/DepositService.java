package com.kevo.wallet.service;

import com.kevo.wallet.entity.Deposit;
import com.kevo.wallet.entity.DepositStatus;
import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.payment.PaymentProvider;
import com.kevo.wallet.repository.DepositRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class DepositService {

    private final DepositRepository depositRepository;
    private final LedgerPostingService ledgerPostingService;
    private final PaymentProvider paymentProvider;

    public DepositService(DepositRepository depositRepository,
                           LedgerPostingService ledgerPostingService,
                           PaymentProvider paymentProvider) {
        this.depositRepository = depositRepository;
        this.ledgerPostingService = ledgerPostingService;
        this.paymentProvider = paymentProvider;
    }

    public Deposit createDeposit(Wallet wallet, BigDecimal amount, String currency, String idempotencyKey) {
        Optional<Deposit> existing = depositRepository.findByWalletIdAndIdempotencyKey(wallet.getId(), idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        PaymentProvider.PaymentResult result = paymentProvider.processDeposit(amount, currency, idempotencyKey);

        if (!result.success()) {
            Deposit failed = new Deposit(wallet.getId(), idempotencyKey, result.providerReference(),
                    paymentProvider.getProviderName(), amount, currency, DepositStatus.FAILED, null);
            return depositRepository.save(failed);
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
}
