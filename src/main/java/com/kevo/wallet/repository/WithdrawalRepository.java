package com.kevo.wallet.repository;

import com.kevo.wallet.entity.Withdrawal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, Long> {
    Optional<Withdrawal> findByWalletIdAndIdempotencyKey(Long walletId, String idempotencyKey);
}
