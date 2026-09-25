package com.kevo.wallet.web;

import com.kevo.wallet.entity.FundLock;
import com.kevo.wallet.service.FundLockService;
import com.kevo.wallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/wallets")
public class FundLockController {

    private final FundLockService fundLockService;
    private final WalletService walletService;

    public FundLockController(FundLockService fundLockService, WalletService walletService) {
        this.fundLockService = fundLockService;
        this.walletService = walletService;
    }

    @PostMapping("/{kevoUserId}/locks")
    public FundLockResponse createLock(@PathVariable Long kevoUserId,
                                        @Valid @RequestBody FundLockRequest request) {
        var wallet = walletService.getOrCreateWallet(kevoUserId);

        FundLock lock;
        try {
            lock = fundLockService.createLock(
                    wallet.getId(), request.amount(), request.currency(),
                    request.marketplaceTransactionId(), request.idempotencyKey());
        } catch (RuntimeException e) {
            lock = fundLockService.findExisting(wallet.getId(), request.idempotencyKey())
                    .orElseThrow(() -> e);
        }

        return toResponse(lock);
    }

    @PostMapping("/{kevoUserId}/locks/release")
    public FundLockResponse releaseLock(@PathVariable Long kevoUserId,
                                         @Valid @RequestBody FundLockReleaseRequest request) {
        var wallet = walletService.getOrCreateWallet(kevoUserId);
        FundLock lock = fundLockService.releaseLock(wallet.getId(), request.marketplaceTransactionId());
        return toResponse(lock);
    }

    @PostMapping("/{kevoUserId}/locks/capture")
    public FundLockResponse captureLock(@PathVariable Long kevoUserId,
                                         @Valid @RequestBody FundLockCaptureRequest request) {
        var buyerWallet = walletService.getOrCreateWallet(kevoUserId);
        FundLock lock = fundLockService.captureLock(
                buyerWallet.getId(), request.marketplaceTransactionId(), request.sellerKevoUserId());
        return toResponse(lock);
    }

    private FundLockResponse toResponse(FundLock lock) {
        return new FundLockResponse(
                lock.getId(),
                lock.getStatus().name(),
                lock.getAmount(),
                lock.getCurrency(),
                lock.getMarketplaceTransactionId(),
                lock.getFailureReason()
        );
    }
}
