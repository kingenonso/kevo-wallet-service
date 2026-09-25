package com.kevo.wallet.web;

import com.kevo.wallet.entity.Withdrawal;
import com.kevo.wallet.service.WalletService;
import com.kevo.wallet.service.WithdrawalService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/wallets")
public class WithdrawalController {

    private final WithdrawalService withdrawalService;
    private final WalletService walletService;

    public WithdrawalController(WithdrawalService withdrawalService, WalletService walletService) {
        this.withdrawalService = withdrawalService;
        this.walletService = walletService;
    }

    @PostMapping("/{kevoUserId}/withdrawals")
    public WithdrawalResponse createWithdrawal(@PathVariable Long kevoUserId,
                                                @Valid @RequestBody WithdrawalRequest request) {
        var wallet = walletService.getOrCreateWallet(kevoUserId);

        Withdrawal withdrawal;
        try {
            withdrawal = withdrawalService.createWithdrawal(
                    wallet.getId(), request.amount(), request.currency(),
                    request.bankAccountId(), request.idempotencyKey());
        } catch (RuntimeException e) {
            withdrawal = withdrawalService.findExisting(wallet.getId(), request.idempotencyKey())
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
                withdrawal.getBankAccountId()
        );
    }
}
