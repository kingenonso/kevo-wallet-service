package com.kevo.wallet.web;

import jakarta.validation.constraints.NotBlank;

public record AddBankAccountRequest(
        @NotBlank String accountHolderName,
        @NotBlank String bankName,
        @NotBlank String accountNumber
) {}
