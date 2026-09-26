package com.kevo.wallet.service;

import com.kevo.wallet.entity.WithdrawalStatus;
import com.kevo.wallet.repository.WithdrawalRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Spec Section 15 (Withdrawal Security) - risk scoring, account-takeover
 * protection, manual review for high-risk withdrawals. Every flag here is
 * a real, binary signal backed by data this service already has, always
 * shown with its reason - never a bare invented score. Note: without any
 * device/IP data reaching this service today (see WithdrawalRequest),
 * true "suspicious device" detection isn't possible yet - these flags
 * cover the account-takeover-shaped behavioral signals that are.
 */
@Service
public class WithdrawalRiskService {

    private static final BigDecimal HISTORY_MULTIPLIER = new BigDecimal("3");
    private static final BigDecimal NEAR_LIMIT_RATIO = new BigDecimal("0.90");
    private static final long RECENT_FAILURE_THRESHOLD = 3;
    private static final long RECENT_FAILURE_WINDOW_HOURS = 1;

    private final WithdrawalRepository withdrawalRepository;

    public WithdrawalRiskService(WithdrawalRepository withdrawalRepository) {
        this.withdrawalRepository = withdrawalRepository;
    }

    public WithdrawalRiskAssessment assess(Long walletId, Long bankAccountId, BigDecimal amount, BigDecimal dailyLimit) {
        List<WithdrawalRiskAssessment.RiskFlag> flags = new ArrayList<>();

        // A wallet's first-ever withdrawal will always have a "new" destination
        // account too, since there's no history at all yet - so these two are
        // only evaluated as separate signals when there IS prior history. The
        // real account-takeover-shaped pattern is an established wallet
        // suddenly paying out to a destination it's never used before, not
        // every legitimate user's normal first withdrawal.
        long completedCount = withdrawalRepository.countByWalletIdAndStatus(walletId, WithdrawalStatus.COMPLETED);
        boolean firstWithdrawalEver = completedCount == 0;
        if (firstWithdrawalEver) {
            flags.add(new WithdrawalRiskAssessment.RiskFlag(
                    "FIRST_WITHDRAWAL_EVER",
                    "This would be the wallet's first-ever completed withdrawal."));
        } else {
            boolean newDestination = !withdrawalRepository.existsByWalletIdAndBankAccountIdAndStatus(
                    walletId, bankAccountId, WithdrawalStatus.COMPLETED);
            if (newDestination) {
                flags.add(new WithdrawalRiskAssessment.RiskFlag(
                        "NEW_DESTINATION_ACCOUNT",
                        "This bank account has never received a completed withdrawal from this wallet before."));
            }
        }

        if (!firstWithdrawalEver) {
            var largest = withdrawalRepository.findTopByWalletIdAndStatusOrderByAmountDesc(
                    walletId, WithdrawalStatus.COMPLETED);
            if (largest.isPresent() && amount.compareTo(largest.get().getAmount().multiply(HISTORY_MULTIPLIER)) > 0) {
                flags.add(new WithdrawalRiskAssessment.RiskFlag(
                        "AMOUNT_FAR_ABOVE_HISTORY",
                        "This withdrawal (" + amount + ") is more than " + HISTORY_MULTIPLIER +
                                "x the largest completed withdrawal this wallet has ever made (" +
                                largest.get().getAmount() + ")."));
            }
        }

        if (dailyLimit != null && dailyLimit.compareTo(BigDecimal.ZERO) > 0
                && amount.compareTo(dailyLimit.multiply(NEAR_LIMIT_RATIO)) >= 0) {
            flags.add(new WithdrawalRiskAssessment.RiskFlag(
                    "NEAR_DAILY_LIMIT",
                    "This single withdrawal uses 90% or more of the daily withdrawal limit."));
        }

        long recentFailures = withdrawalRepository.countByWalletIdAndStatusAndCreatedAtAfter(
                walletId, WithdrawalStatus.FAILED, LocalDateTime.now().minusHours(RECENT_FAILURE_WINDOW_HOURS));
        boolean repeatedFailures = recentFailures >= RECENT_FAILURE_THRESHOLD;
        if (repeatedFailures) {
            flags.add(new WithdrawalRiskAssessment.RiskFlag(
                    "REPEATED_RECENT_FAILURES",
                    recentFailures + " failed withdrawal attempts from this wallet in the last hour - possible probing or account-takeover activity."));
        }

        String riskLevel;
        if (repeatedFailures || flags.size() >= 2) {
            riskLevel = "HIGH";
        } else if (flags.size() == 1) {
            riskLevel = "ELEVATED";
        } else {
            riskLevel = "LOW";
        }

        return new WithdrawalRiskAssessment(riskLevel, flags);
    }
}
