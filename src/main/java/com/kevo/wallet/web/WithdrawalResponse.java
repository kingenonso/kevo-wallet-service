package com.kevo.wallet.web;

import java.math.BigDecimal;

public record WithdrawalResponse(
        Long id,
        String status,
        BigDecimal amount,
        String currency,
        String destinationReference,
        String failureReason,
        String providerReference
) {}
