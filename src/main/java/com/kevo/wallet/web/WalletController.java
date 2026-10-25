package com.kevo.wallet.web;

import com.kevo.wallet.entity.Bucket;
import com.kevo.wallet.entity.LedgerEntry;
import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.service.StripeConnectService;
import com.kevo.wallet.service.WalletService;
import com.stripe.exception.StripeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
public class WalletController {

    private final WalletService walletService;
    private final StripeConnectService stripeConnectService;

    public WalletController(WalletService walletService, StripeConnectService stripeConnectService) {
        this.walletService = walletService;
        this.stripeConnectService = stripeConnectService;
    }

    @GetMapping("/internal/wallets/{kevoUserId}")
    public WalletResponse getWallet(@PathVariable Long kevoUserId) {
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);
        Map<Bucket, BigDecimal> balances = walletService.computeBalances(wallet.getId());

        BigDecimal available = balances.get(Bucket.AVAILABLE);
        BigDecimal pending = balances.get(Bucket.PENDING);
        BigDecimal locked = balances.get(Bucket.LOCKED);
        BigDecimal withdrawalPending = balances.get(Bucket.WITHDRAWAL_PENDING);
        BigDecimal total = available.add(pending).add(locked).add(withdrawalPending);

        return new WalletResponse(
                wallet.getKevoUserId(),
                wallet.getCurrency(),
                wallet.getStatus().name(),
                available,
                pending,
                locked,
                withdrawalPending,
                total
        );
    }

    @GetMapping("/internal/wallets/{kevoUserId}/ledger-entries")
    public List<LedgerEntryResponse> getLedgerEntries(
            @PathVariable Long kevoUserId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);
        return walletService.getLedgerEntries(wallet.getId(), limit, offset).stream()
                .map(entry -> new LedgerEntryResponse(
                        entry.getId(),
                        entry.getLedgerTransactionId(),
                        entry.getDirection().name(),
                        entry.getBucket().name(),
                        entry.getEntryType(),
                        entry.getAmount(),
                        entry.getCurrency(),
                        entry.getDescription(),
                        entry.getRelatedMarketplaceTransactionId(),
                        entry.getReversesEntryId(),
                        entry.getCreatedAt()
                ))
                .toList();
    }

    @PostMapping("/internal/wallets/{kevoUserId}/connect/account")
    public ResponseEntity<?> createConnectAccount(@PathVariable Long kevoUserId,
                                                    @Valid @RequestBody ConnectAccountRequest request) {
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);
        try {
            String accountId = stripeConnectService.getOrCreateConnectAccount(
                    wallet.getId(), request.country(), request.email());
            return ResponseEntity.ok(new ConnectAccountResponse(accountId));
        } catch (StripeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/internal/wallets/{kevoUserId}/connect/onboarding-link")
    public ResponseEntity<?> createOnboardingLink(@PathVariable Long kevoUserId,
                                                    @Valid @RequestBody OnboardingLinkRequest request) {
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);
        if (wallet.getStripeConnectAccountId() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("No Stripe Connect account exists for this wallet yet."));
        }
        try {
            String url = stripeConnectService.createOnboardingLink(
                    wallet.getStripeConnectAccountId(), request.refreshUrl(), request.returnUrl());
            return ResponseEntity.ok(new OnboardingLinkResponse(url));
        } catch (StripeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/internal/wallets/{kevoUserId}/connect/status")
    public ResponseEntity<?> getConnectStatus(@PathVariable Long kevoUserId) {
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);
        try {
            boolean payoutsEnabled = stripeConnectService.refreshPayoutsEnabled(wallet.getId());
            return ResponseEntity.ok(new ConnectStatusResponse(
                    wallet.getStripeConnectAccountId() != null, payoutsEnabled));
        } catch (StripeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(e.getMessage()));
        }
    }

    public record ConnectAccountRequest(@NotBlank String country, @NotBlank String email) {}
    public record ConnectAccountResponse(String stripeConnectAccountId) {}
    public record OnboardingLinkRequest(@NotBlank String refreshUrl, @NotBlank String returnUrl) {}
    public record OnboardingLinkResponse(String url) {}
    public record ConnectStatusResponse(boolean accountCreated, boolean payoutsEnabled) {}
    public record ErrorResponse(String message) {}
    public record LedgerEntryResponse(
            Long id,
            String ledgerTransactionId,
            String direction,
            String bucket,
            String entryType,
            BigDecimal amount,
            String currency,
            String description,
            Long relatedMarketplaceTransactionId,
            Long reversesEntryId,
            Instant createdAt
    ) {}

    public record WalletResponse(
            Long kevoUserId,
            String currency,
            String status,
            BigDecimal available,
            BigDecimal pending,
            BigDecimal locked,
            BigDecimal withdrawalPending,
            BigDecimal total
    ) {}
}
