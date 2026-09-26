package com.kevo.wallet.web;

import com.kevo.wallet.entity.Withdrawal;
import com.kevo.wallet.entity.WithdrawalStatus;
import com.kevo.wallet.repository.WalletRepository;
import com.kevo.wallet.repository.WithdrawalRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Cross-wallet admin visibility into withdrawals held for manual review.
// Deliberately separate from WithdrawalController, which is always scoped
// to one kevoUserId - an admin reviewing the queue doesn't start out
// knowing which wallet a flagged withdrawal belongs to, this is how they
// find out, before calling WithdrawalController's approve/reject for a
// specific one (which re-checks that withdrawal really belongs to the
// wallet id passed in, so this listing can't be used to bypass that check).
@RestController
@RequestMapping("/internal/withdrawals")
public class WithdrawalReviewController {

    private final WithdrawalRepository withdrawalRepository;
    private final WalletRepository walletRepository;

    public WithdrawalReviewController(WithdrawalRepository withdrawalRepository, WalletRepository walletRepository) {
        this.withdrawalRepository = withdrawalRepository;
        this.walletRepository = walletRepository;
    }

    @GetMapping("/pending-review")
    public List<WithdrawalReviewResponse> pendingReview() {
        List<Withdrawal> held = withdrawalRepository.findAllByStatusOrderByCreatedAtAsc(WithdrawalStatus.PENDING_REVIEW);
        return held.stream()
                .map(w -> new WithdrawalReviewResponse(
                        w.getId(),
                        walletRepository.findById(w.getWalletId()).map(wallet -> wallet.getKevoUserId()).orElse(null),
                        w.getWalletId(),
                        w.getAmount(),
                        w.getCurrency(),
                        w.getBankAccountId(),
                        w.getRiskLevel(),
                        w.getReviewNotes(),
                        w.getCreatedAt()
                ))
                .toList();
    }
}
