package com.kevo.wallet.web;

import com.kevo.wallet.entity.Withdrawal;
import com.kevo.wallet.service.RealWithdrawalService;
import com.kevo.wallet.service.WalletService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
public class RealWithdrawalController {

    private final WalletService walletService;
    private final RealWithdrawalService realWithdrawalService;

    public RealWithdrawalController(WalletService walletService, RealWithdrawalService realWithdrawalService) {
        this.walletService = walletService;
        this.realWithdrawalService = realWithdrawalService;
    }

    @PostMapping("/internal/wallets/{kevoUserId}/withdrawals/real")
    public WithdrawalResponse createRealWithdrawal(@PathVariable Long kevoUserId,
                                                     @Valid @RequestBody RealWithdrawalRequest request) {
        var wallet = walletService.getOrCreateWallet(kevoUserId);

        Withdrawal withdrawal;
        try {
            withdrawal = realWithdrawalService.createRealWithdrawal(
                    wallet.getId(), request.amount(), request.currency(), request.idempotencyKey());
        } catch (RuntimeException e) {
            withdrawal = realWithdrawalService.findExisting(wallet.getId(), request.idempotencyKey())
                    .orElseThrow(() -> e);
        }

        return new WithdrawalResponse(
                withdrawal.getId(),
                withdrawal.getStatus().name(),
                withdrawal.getAmount(),
                withdrawal.getCurrency(),
                withdrawal.getDestinationReference(),
                withdrawal.getFailureReason(),
                withdrawal.getProviderReference(),
                withdrawal.getBankAccountId(),
                withdrawal.getRiskLevel(),
                withdrawal.getReviewNotes()
        );
    }

    public record RealWithdrawalRequest(
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotBlank String currency,
            @NotBlank String idempotencyKey
    ) {}
}
