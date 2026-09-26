package com.kevo.wallet.web;

import com.kevo.wallet.entity.Deposit;
import com.kevo.wallet.service.DepositReversalService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * Demo entry point for a deposit reversal/chargeback (spec Section 17). In
 * production this action would be driven by a verified payment-provider
 * webhook event, not called directly - this endpoint exists so the reversal
 * logic itself is real and tested even though no live provider exists yet.
 */
@RestController
@RequestMapping("/internal/wallets/{kevoUserId}/deposits")
public class DepositReversalController {

    private final DepositReversalService depositReversalService;

    public DepositReversalController(DepositReversalService depositReversalService) {
        this.depositReversalService = depositReversalService;
    }

    @PostMapping("/{depositId}/reverse")
    public ResponseEntity<?> reverseDeposit(@PathVariable Long kevoUserId,
                                             @PathVariable Long depositId,
                                             @RequestBody ReverseDepositRequest request) {
        try {
            Deposit deposit = depositReversalService.reverseDeposit(kevoUserId, depositId, request.reason());
            return ResponseEntity.ok(new DepositReversalResponse(
                    deposit.getId(), deposit.getStatus().name(), deposit.getAmount(), deposit.getCurrency()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    public record ReverseDepositRequest(@NotBlank String reason) {}
    public record DepositReversalResponse(Long id, String status, BigDecimal amount, String currency) {}
    public record ErrorResponse(String message) {}
}
