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

        return toResponse(withdrawal);
    }

    // High-risk withdrawals are held in PENDING_REVIEW rather than sent to
    // the payment provider - these two let an admin resolve one. Scoped
    // under the same kevoUserId path as everything else here so the
    // withdrawal's actual wallet is checked against the caller's claimed
    // identity, not just trusted from the withdrawal id alone.
    @PutMapping("/{kevoUserId}/withdrawals/{withdrawalId}/approve")
    public WithdrawalResponse approveWithdrawal(@PathVariable Long kevoUserId, @PathVariable Long withdrawalId) {
        var wallet = walletService.getOrCreateWallet(kevoUserId);
        Withdrawal withdrawal = withdrawalService.approveReviewedWithdrawal(withdrawalId, wallet.getId());
        return toResponse(withdrawal);
    }

    @PutMapping("/{kevoUserId}/withdrawals/{withdrawalId}/reject")
    public WithdrawalResponse rejectWithdrawal(@PathVariable Long kevoUserId, @PathVariable Long withdrawalId,
                                                @RequestBody RejectWithdrawalRequest request) {
        var wallet = walletService.getOrCreateWallet(kevoUserId);
        Withdrawal withdrawal = withdrawalService.rejectReviewedWithdrawal(withdrawalId, wallet.getId(), request.reason());
        return toResponse(withdrawal);
    }

    private WithdrawalResponse toResponse(Withdrawal withdrawal) {
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
}
