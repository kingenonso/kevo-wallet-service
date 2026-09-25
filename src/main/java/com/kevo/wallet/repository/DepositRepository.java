package com.kevo.wallet.repository;

import com.kevo.wallet.entity.Deposit;
import com.kevo.wallet.entity.DepositStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public interface DepositRepository extends JpaRepository<Deposit, Long> {
    Optional<Deposit> findByWalletIdAndIdempotencyKey(Long walletId, String idempotencyKey);

    @Query("SELECT COALESCE(SUM(d.amount), 0) FROM Deposit d " +
           "WHERE d.walletId = :walletId AND d.status = :status AND d.createdAt >= :since")
    BigDecimal sumAmountByWalletIdAndStatusAndCreatedAtAfter(@Param("walletId") Long walletId,
                                                              @Param("status") DepositStatus status,
                                                              @Param("since") Instant since);
}
