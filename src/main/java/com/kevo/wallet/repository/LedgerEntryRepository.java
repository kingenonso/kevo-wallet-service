package com.kevo.wallet.repository;

import com.kevo.wallet.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    List<LedgerEntry> findByWalletId(Long walletId);
    List<LedgerEntry> findByLedgerTransactionId(String ledgerTransactionId);
}
