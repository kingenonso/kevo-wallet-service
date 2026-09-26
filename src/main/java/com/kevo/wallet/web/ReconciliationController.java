package com.kevo.wallet.web;

import com.kevo.wallet.service.ReconciliationService;
import com.kevo.wallet.service.WalletService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Read-only reconciliation endpoints (spec Section 24). These never modify
 * any financial record - they only report what they find, so a real
 * inconsistency fails loudly and safely instead of being silently "fixed."
 */
@RestController
public class ReconciliationController {

    private final ReconciliationService reconciliationService;
    private final WalletService walletService;

    public ReconciliationController(ReconciliationService reconciliationService, WalletService walletService) {
        this.reconciliationService = reconciliationService;
        this.walletService = walletService;
    }

    @GetMapping("/internal/wallets/{kevoUserId}/reconciliation")
    public ReconciliationService.ReconciliationReport reconcileWallet(@PathVariable Long kevoUserId) {
        var wallet = walletService.getOrCreateWallet(kevoUserId);
        return reconciliationService.reconcileWallet(wallet.getId());
    }

    @GetMapping("/internal/reconciliation")
    public List<ReconciliationService.ReconciliationReport> reconcileAll() {
        return reconciliationService.reconcileAllWallets();
    }
}
