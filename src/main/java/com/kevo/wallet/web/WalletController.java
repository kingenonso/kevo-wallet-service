package com.kevo.wallet.web;

import com.kevo.wallet.entity.Bucket;
import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.service.WalletService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
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
