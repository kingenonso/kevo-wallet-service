package com.kevo.wallet.service;

import com.kevo.wallet.entity.Bucket;
import com.kevo.wallet.entity.Direction;
import com.kevo.wallet.entity.LedgerEntry;
import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.repository.LedgerEntryRepository;
import com.kevo.wallet.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class WalletService {

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public WalletService(WalletRepository walletRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public Wallet getOrCreateWallet(Long kevoUserId) {
        return walletRepository.findByKevoUserId(kevoUserId)
                .orElseGet(() -> walletRepository.save(new Wallet(kevoUserId, "USD")));
    }

    public Map<Bucket, BigDecimal> computeBalances(Long walletId) {
        Map<Bucket, BigDecimal> balances = new EnumMap<>(Bucket.class);
        for (Bucket bucket : Bucket.values()) {
            balances.put(bucket, BigDecimal.ZERO);
        }

        List<LedgerEntry> entries = ledgerEntryRepository.findByWalletId(walletId);
        for (LedgerEntry entry : entries) {
            BigDecimal current = balances.get(entry.getBucket());
            if (entry.getDirection() == Direction.CREDIT) {
                balances.put(entry.getBucket(), current.add(entry.getAmount()));
            } else {
                balances.put(entry.getBucket(), current.subtract(entry.getAmount()));
            }
        }
        return balances;
    }
}
