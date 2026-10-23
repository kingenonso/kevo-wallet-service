package com.kevo.wallet.web;

import com.kevo.wallet.entity.Deposit;
import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.service.RealDepositService;
import com.kevo.wallet.service.WalletService;
import com.stripe.exception.StripeException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
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
        long amountCents = request.amount().multiply(BigDecimal.valueOf(100)).longValueExact();

        Deposit deposit;
        try {
            deposit = realDepositService.createRealDeposit(
                    wallet.getId(), request.bankAccountId(), amountCents, request.idempotencyKey());
        } catch (StripeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        } catch (RuntimeException e) {
            // Defensive fallback for a genuine concurrent double-submit that
            // slips past the idempotency check inside the service before
            // either request's save has committed - if another request
            // already landed the deposit, return that instead of a 500.
            deposit = realDepositService.findExisting(wallet.getId(), request.idempotencyKey())
                    .orElseThrow(() -> e);
        }

        return ResponseEntity.ok(new RealDepositResponse(deposit.getId(), deposit.getProviderReference(), deposit.getStatus().name()));
    }

    public record RealDepositRequest(@NotNull Long bankAccountId, @NotNull @DecimalMin("0.01") BigDecimal amount,
                                      @NotBlank String idempotencyKey) {}
    public record RealDepositResponse(Long depositId, String paymentIntentId, String status) {}
    public record ErrorResponse(String message) {}
}
