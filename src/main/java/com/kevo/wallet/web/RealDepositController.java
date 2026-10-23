package com.kevo.wallet.web;

import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.service.RealDepositService;
import com.kevo.wallet.service.WalletService;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
public class RealDepositController {

    private final WalletService walletService;
    private final RealDepositService realDepositService;

    public RealDepositController(WalletService walletService, RealDepositService realDepositService) {
        this.walletService = walletService;
        this.realDepositService = realDepositService;
    }

    @PostMapping("/internal/wallets/{kevoUserId}/deposits/real")
    public ResponseEntity<?> createRealDeposit(@PathVariable Long kevoUserId,
                                                 @Valid @RequestBody RealDepositRequest request) {
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);
        try {
            long amountCents = request.amount().multiply(BigDecimal.valueOf(100)).longValueExact();
            PaymentIntent intent = realDepositService.createRealDeposit(wallet.getId(), request.bankAccountId(), amountCents);
            return ResponseEntity.ok(new RealDepositResponse(intent.getId(), intent.getStatus()));
        } catch (StripeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        }
    }

    public record RealDepositRequest(@NotNull Long bankAccountId, @NotNull @DecimalMin("0.01") BigDecimal amount) {}
    public record RealDepositResponse(String paymentIntentId, String status) {}
    public record ErrorResponse(String message) {}
}
