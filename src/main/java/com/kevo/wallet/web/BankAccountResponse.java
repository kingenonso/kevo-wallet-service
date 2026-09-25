package com.kevo.wallet.web;

import java.time.LocalDateTime;

public record BankAccountResponse(
        Long id,
        String accountHolderName,
        String bankName,
        String lastFour,
        String status,
        LocalDateTime createdAt
) {}
