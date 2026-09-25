package com.kevo.wallet.repository;

import com.kevo.wallet.entity.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {
    List<BankAccount> findByWalletId(Long walletId);
    Optional<BankAccount> findByIdAndWalletId(Long id, Long walletId);
}
