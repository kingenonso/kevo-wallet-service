package com.kevo.wallet.repository;

import com.kevo.wallet.entity.Withdrawal;
import com.kevo.wallet.entity.WithdrawalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, Long> {
    Optional<Withdrawal> findByWalletIdAndIdempotencyKey(Long walletId, String idempotencyKey);

    @Query("SELECT COALESCE(SUM(w.amount), 0) FROM Withdrawal w " +
           "WHERE w.walletId = :walletId AND w.status = :status AND w.createdAt >= :since")
    BigDecimal sumAmountByWalletIdAndStatusAndCreatedAtAfter(@Param("walletId") Long walletId,
                                                              @Param("status") WithdrawalStatus status,
                                                              @Param("since") LocalDateTime since);

    boolean existsByWalletIdAndBankAccountIdAndStatus(Long walletId, Long bankAccountId, WithdrawalStatus status);

    long countByWalletIdAndStatus(Long walletId, WithdrawalStatus status);

    long countByWalletIdAndStatusAndCreatedAtAfter(Long walletId, WithdrawalStatus status, LocalDateTime since);

    Optional<Withdrawal> findTopByWalletIdAndStatusOrderByAmountDesc(Long walletId, WithdrawalStatus status);
}
