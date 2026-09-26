package com.kevo.wallet.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// A withdrawal held in PENDING_REVIEW, as an admin needs to see it to decide:
// which wallet/user it belongs to, how much, to which bank account, and why
// it was flagged (riskLevel/reviewNotes come straight from WithdrawalRiskService).
public record WithdrawalReviewResponse(
        Long id,
        Long kevoUserId,
        Long walletId,
        BigDecimal amount,
        String currency,
        Long bankAccountId,
        String riskLevel,
        String reviewNotes,
        LocalDateTime createdAt
) {
}
