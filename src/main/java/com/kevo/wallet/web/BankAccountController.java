package com.kevo.wallet.web;

import com.kevo.wallet.entity.BankAccount;
import com.kevo.wallet.service.BankAccountService;
import com.kevo.wallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/internal/wallets/{kevoUserId}/bank-accounts")
public class BankAccountController {

    private final BankAccountService bankAccountService;
    private final WalletService walletService;

    public BankAccountController(BankAccountService bankAccountService, WalletService walletService) {
        this.bankAccountService = bankAccountService;
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<?> addBankAccount(@PathVariable Long kevoUserId,
                                             @Valid @RequestBody AddBankAccountRequest request) {
        var wallet = walletService.getOrCreateWallet(kevoUserId);
        try {
            BankAccount account = bankAccountService.addBankAccount(
                    wallet.getId(), request.accountHolderName(), request.bankName(), request.accountNumber());
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(account));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping
    public List<BankAccountResponse> listBankAccounts(@PathVariable Long kevoUserId) {
        var wallet = walletService.getOrCreateWallet(kevoUserId);
        return bankAccountService.listBankAccounts(wallet.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @PostMapping("/{bankAccountId}/verify")
    public ResponseEntity<?> verifyBankAccount(@PathVariable Long kevoUserId, @PathVariable Long bankAccountId) {
        var wallet = walletService.getOrCreateWallet(kevoUserId);
        try {
            BankAccount account = bankAccountService.verifyBankAccount(bankAccountId, wallet.getId());
            return ResponseEntity.ok(toResponse(account));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/{bankAccountId}/disable")
    public ResponseEntity<?> disableBankAccount(@PathVariable Long kevoUserId, @PathVariable Long bankAccountId) {
        var wallet = walletService.getOrCreateWallet(kevoUserId);
        try {
            BankAccount account = bankAccountService.disableBankAccount(bankAccountId, wallet.getId());
            return ResponseEntity.ok(toResponse(account));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/connect-session")
    public ResponseEntity<?> createConnectSession(@PathVariable Long kevoUserId,
                                                    @Valid @RequestBody ConnectSessionRequest request) {
        var wallet = walletService.getOrCreateWallet(kevoUserId);
        try {
            String clientSecret = bankAccountService.createBankConnectionSession(wallet.getId(), request.accountHolderName());
            return ResponseEntity.ok(new ConnectSessionResponse(clientSecret));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/connect-finalize")
    public ResponseEntity<?> finalizeConnectSession(@PathVariable Long kevoUserId,
                                                      @Valid @RequestBody FinalizeConnectRequest request) {
        var wallet = walletService.getOrCreateWallet(kevoUserId);
        try {
            List<BankAccount> accounts = bankAccountService.finalizeBankConnection(
                    wallet.getId(), request.sessionId(), request.accountHolderName());
            return ResponseEntity.ok(accounts.stream().map(this::toResponse).toList());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(e.getMessage()));
        }
    }

    public record ConnectSessionRequest(@jakarta.validation.constraints.NotBlank String accountHolderName) {}
    public record ConnectSessionResponse(String clientSecret) {}
    public record FinalizeConnectRequest(@jakarta.validation.constraints.NotBlank String sessionId,
                                          @jakarta.validation.constraints.NotBlank String accountHolderName) {}

    private BankAccountResponse toResponse(BankAccount account) {
        return new BankAccountResponse(
                account.getId(),
                account.getAccountHolderName(),
                account.getBankName(),
                account.getLastFour(),
                account.getStatus().name(),
                account.getCreatedAt()
        );
    }

    public record ErrorResponse(String message) {}
}
