package com.kevo.wallet.web;

import java.math.BigDecimal;

public record FundLockResponse(
        Long id,
        String status,
        BigDecimal amount,
        String currency,
        Long marketplaceTransactionId,
        String failureReason
) {}
