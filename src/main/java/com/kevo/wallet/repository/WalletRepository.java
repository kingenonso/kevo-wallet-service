package com.kevo.wallet.repository;

import com.kevo.wallet.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {
    Optional<Wallet> findByKevoUserId(Long kevoUserId);

    // Locks the wallet row for the duration of the enclosing transaction.
    // Any other transaction trying to lock the SAME wallet (another withdrawal
    // or a future funds-lock operation) blocks until this one commits or rolls
    // back - this is what actually prevents two concurrent withdrawals from
    // both reading the same starting balance and both succeeding.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") Long id);
}
