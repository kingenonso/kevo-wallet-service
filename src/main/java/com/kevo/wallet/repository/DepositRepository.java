package com.kevo.wallet.repository;

import com.kevo.wallet.entity.Deposit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DepositRepository extends JpaRepository<Deposit, Long> {
    Optional<Deposit> findByWalletIdAndIdempotencyKey(Long walletId, String idempotencyKey);
}
