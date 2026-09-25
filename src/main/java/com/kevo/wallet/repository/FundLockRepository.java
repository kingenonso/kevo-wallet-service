package com.kevo.wallet.repository;

import com.kevo.wallet.entity.FundLock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FundLockRepository extends JpaRepository<FundLock, Long> {
    Optional<FundLock> findByWalletIdAndIdempotencyKey(Long walletId, String idempotencyKey);
    Optional<FundLock> findByWalletIdAndMarketplaceTransactionId(Long walletId, Long marketplaceTransactionId);
}
