package com.kevo.wallet.web;

import jakarta.validation.constraints.NotNull;

public record FundLockCaptureRequest(
        @NotNull Long marketplaceTransactionId,
        @NotNull Long sellerKevoUserId
) {}
