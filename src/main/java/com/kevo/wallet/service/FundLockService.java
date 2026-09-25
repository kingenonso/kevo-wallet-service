package com.kevo.wallet.service;

import com.kevo.wallet.entity.*;
import com.kevo.wallet.repository.FundLockRepository;
import com.kevo.wallet.repository.LedgerEntryRepository;
import com.kevo.wallet.repository.WalletRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class FundLockService {

    private final FundLockRepository fundLockRepository;
    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final WalletService walletService;

    public FundLockService(FundLockRepository fundLockRepository,
                            WalletRepository walletRepository,
                            LedgerEntryRepository ledgerEntryRepository,
                            WalletService walletService) {
        this.fundLockRepository = fundLockRepository;
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.walletService = walletService;
    }

    @Transactional
    public FundLock createLock(Long walletId, BigDecimal amount, String currency,
                                Long marketplaceTransactionId, String idempotencyKey) {

        var existing = fundLockRepository.findByWalletIdAndIdempotencyKey(walletId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        Wallet wallet = walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));

        Map<Bucket, BigDecimal> balances = walletService.computeBalances(walletId);
        BigDecimal available = balances.getOrDefault(Bucket.AVAILABLE, BigDecimal.ZERO);

        if (available.compareTo(amount) < 0) {
            return saveNew(new FundLock(wallet.getId(), marketplaceTransactionId, idempotencyKey,
                    amount, currency, FundLockStatus.FAILED, "Insufficient available funds", null),
                    walletId, idempotencyKey);
        }

        String ledgerTransactionId = UUID.randomUUID().toString();

        LedgerEntry debitAvailable = new LedgerEntry(
                wallet.getId(), ledgerTransactionId, Direction.DEBIT, Bucket.AVAILABLE, "LOCK",
                amount, currency, "Funds locked for marketplace transaction " + marketplaceTransactionId,
                marketplaceTransactionId, null);
        ledgerEntryRepository.save(debitAvailable);

        LedgerEntry creditLocked = new LedgerEntry(
                wallet.getId(), ledgerTransactionId, Direction.CREDIT, Bucket.LOCKED, "LOCK",
                amount, currency, "Funds locked for marketplace transaction " + marketplaceTransactionId,
                marketplaceTransactionId, null);
        creditLocked = ledgerEntryRepository.save(creditLocked);

        FundLock lock = new FundLock(wallet.getId(), marketplaceTransactionId, idempotencyKey,
                amount, currency, FundLockStatus.ACTIVE, null, creditLocked.getId());

        return saveNew(lock, walletId, idempotencyKey);
    }

    @Transactional
    public FundLock releaseLock(Long walletId, Long marketplaceTransactionId) {

        FundLock lock = fundLockRepository.findByWalletIdAndMarketplaceTransactionId(walletId, marketplaceTransactionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No fund lock found for wallet " + walletId + " and marketplace transaction " + marketplaceTransactionId));

        if (lock.getStatus() == FundLockStatus.RELEASED) {
            return lock;
        }
        if (lock.getStatus() != FundLockStatus.ACTIVE) {
            throw new IllegalStateException("Cannot release a fund lock in status " + lock.getStatus());
        }

        Wallet wallet = walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));

        String ledgerTransactionId = UUID.randomUUID().toString();

        LedgerEntry debitLocked = new LedgerEntry(
                wallet.getId(), ledgerTransactionId, Direction.DEBIT, Bucket.LOCKED, "RELEASE",
                lock.getAmount(), lock.getCurrency(),
                "Funds released for marketplace transaction " + marketplaceTransactionId,
                marketplaceTransactionId, null);
        ledgerEntryRepository.save(debitLocked);

        LedgerEntry creditAvailable = new LedgerEntry(
                wallet.getId(), ledgerTransactionId, Direction.CREDIT, Bucket.AVAILABLE, "RELEASE",
                lock.getAmount(), lock.getCurrency(),
                "Funds released for marketplace transaction " + marketplaceTransactionId,
                marketplaceTransactionId, null);
        creditAvailable = ledgerEntryRepository.save(creditAvailable);

        lock.markReleased(creditAvailable.getId());
        return fundLockRepository.save(lock);
    }

    @Transactional
    public FundLock captureLock(Long buyerWalletId, Long marketplaceTransactionId, Long sellerKevoUserId) {

        FundLock lock = fundLockRepository.findByWalletIdAndMarketplaceTransactionId(buyerWalletId, marketplaceTransactionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No fund lock found for wallet " + buyerWalletId + " and marketplace transaction " + marketplaceTransactionId));

        if (lock.getStatus() == FundLockStatus.CAPTURED) {
            return lock;
        }
        if (lock.getStatus() != FundLockStatus.ACTIVE) {
            throw new IllegalStateException("Cannot capture a fund lock in status " + lock.getStatus());
        }

        Wallet sellerWallet = walletService.getOrCreateWallet(sellerKevoUserId);
        Long sellerWalletId = sellerWallet.getId();

        Long firstId = Math.min(buyerWalletId, sellerWalletId);
        Long secondId = Math.max(buyerWalletId, sellerWalletId);

        walletRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + firstId));
        if (!firstId.equals(secondId)) {
            walletRepository.findByIdForUpdate(secondId)
                    .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + secondId));
        }

        String ledgerTransactionId = UUID.randomUUID().toString();

        LedgerEntry debitBuyerLocked = new LedgerEntry(
                buyerWalletId, ledgerTransactionId, Direction.DEBIT, Bucket.LOCKED, "CAPTURE",
                lock.getAmount(), lock.getCurrency(),
                "Funds captured for marketplace transaction " + marketplaceTransactionId,
                marketplaceTransactionId, null);
        ledgerEntryRepository.save(debitBuyerLocked);

        LedgerEntry creditSellerAvailable = new LedgerEntry(
                sellerWalletId, ledgerTransactionId, Direction.CREDIT, Bucket.AVAILABLE, "CAPTURE",
                lock.getAmount(), lock.getCurrency(),
                "Settlement proceeds received for marketplace transaction " + marketplaceTransactionId,
                marketplaceTransactionId, null);
        creditSellerAvailable = ledgerEntryRepository.save(creditSellerAvailable);

        lock.markCaptured(creditSellerAvailable.getId());
        return fundLockRepository.save(lock);
    }

    @Transactional(readOnly = true)
    public Optional<FundLock> findExisting(Long walletId, String idempotencyKey) {
        return fundLockRepository.findByWalletIdAndIdempotencyKey(walletId, idempotencyKey);
    }

    private FundLock saveNew(FundLock lock, Long walletId, String idempotencyKey) {
        try {
            return fundLockRepository.save(lock);
        } catch (DataIntegrityViolationException e) {
            return fundLockRepository.findByWalletIdAndIdempotencyKey(walletId, idempotencyKey)
                    .orElseThrow(() -> e);
        }
    }
}
