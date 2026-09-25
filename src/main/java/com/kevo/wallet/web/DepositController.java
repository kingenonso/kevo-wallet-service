package com.kevo.wallet.web;

import com.kevo.wallet.entity.Deposit;
import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.service.DepositService;
import com.kevo.wallet.service.WalletService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
public class DepositController {

    private final WalletService walletService;
    private final DepositService depositService;

    public DepositController(WalletService walletService, DepositService depositService) {
        this.walletService = walletService;
        this.depositService = depositService;
    }

    @PostMapping("/internal/wallets/{kevoUserId}/deposits")
    public DepositResponse deposit(@PathVariable Long kevoUserId, @Valid @RequestBody DepositRequest request) {
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);
        Deposit deposit = depositService.createDeposit(wallet, request.amount(), request.currency(), request.idempotencyKey());

        return new DepositResponse(
                deposit.getId(),
                deposit.getWalletId(),
                deposit.getStatus().name(),
                deposit.getAmount(),
                deposit.getCurrency(),
                deposit.getProviderReference(),
                deposit.getIdempotencyKey()
        );
    }

    public record DepositRequest(
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            @NotBlank String currency,
            @NotBlank String idempotencyKey
    ) {}

    public record DepositResponse(
            Long id,
            Long walletId,
            String status,
            BigDecimal amount,
            String currency,
            String providerReference,
            String idempotencyKey
    ) {}
}
