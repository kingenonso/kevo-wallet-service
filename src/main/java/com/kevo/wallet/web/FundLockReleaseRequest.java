package com.kevo.wallet.web;

import jakarta.validation.constraints.NotNull;

public record FundLockReleaseRequest(
        @NotNull Long marketplaceTransactionId
) {}
