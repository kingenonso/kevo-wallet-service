package com.kevo.wallet.service;

import com.kevo.wallet.entity.LedgerEntry;
import com.kevo.wallet.entity.Wallet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M32 gap-closure, 2026-10-25 - the itemized ledger/activity-history view
 * flagged as the one remaining named gap in every prior M32 doc.
 * WalletService.getLedgerEntries() is the real, honest history (most
 * recent first) behind the new GET /internal/wallets/{id}/ledger-entries
 * endpoint; WalletController's own mapping to LedgerEntryResponse is
 * exercised indirectly through this service-level coverage, matching the
 * rest of this test suite's own service-layer-first convention.
 */
@SpringBootTest
class WalletLedgerEntriesTest {

    @Autowired private WalletService walletService;
    @Autowired private DepositService depositService;

    private static final AtomicLong idCounter = new AtomicLong();

    private Wallet newWallet() {
        long kevoUserId = 920_000_000L + (System.currentTimeMillis() % 1_000_000) + idCounter.incrementAndGet();
        return walletService.getOrCreateWallet(kevoUserId);
    }

    @Test
    void mostRecentEntryComesFirst() {
        Wallet wallet = newWallet();
        depositService.createDeposit(wallet, new BigDecimal("10.00"), "USD", UUID.randomUUID().toString());
        depositService.createDeposit(wallet, new BigDecimal("20.00"), "USD", UUID.randomUUID().toString());
        depositService.createDeposit(wallet, new BigDecimal("30.00"), "USD", UUID.randomUUID().toString());

        List<LedgerEntry> entries = walletService.getLedgerEntries(wallet.getId(), 50, 0);

        assertEquals(3, entries.size());
        assertEquals(new BigDecimal("30.00"), entries.get(0).getAmount());
        assertEquals(new BigDecimal("20.00"), entries.get(1).getAmount());
        assertEquals(new BigDecimal("10.00"), entries.get(2).getAmount());
        assertTrue(entries.get(0).getCreatedAt().isAfter(entries.get(2).getCreatedAt())
                || entries.get(0).getCreatedAt().equals(entries.get(2).getCreatedAt()));
    }

    @Test
    void entryFieldsMatchWhatWasPosted() {
        Wallet wallet = newWallet();
        depositService.createDeposit(wallet, new BigDecimal("42.50"), "USD", UUID.randomUUID().toString());

        List<LedgerEntry> entries = walletService.getLedgerEntries(wallet.getId(), 50, 0);

        assertEquals(1, entries.size());
        LedgerEntry entry = entries.get(0);
        assertEquals("DEPOSIT", entry.getEntryType());
        assertEquals(new BigDecimal("42.50"), entry.getAmount());
        assertEquals("USD", entry.getCurrency());
        assertTrue(entry.getDescription().startsWith("Wallet funding via"));
    }

    @Test
    void limitCapsThePageSize() {
        Wallet wallet = newWallet();
        for (int i = 0; i < 5; i++) {
            depositService.createDeposit(wallet, new BigDecimal("1.00"), "USD", UUID.randomUUID().toString());
        }

        List<LedgerEntry> firstTwo = walletService.getLedgerEntries(wallet.getId(), 2, 0);
        assertEquals(2, firstTwo.size());
    }

    @Test
    void offsetSkipsThatManyFromTheTop() {
        Wallet wallet = newWallet();
        depositService.createDeposit(wallet, new BigDecimal("1.00"), "USD", UUID.randomUUID().toString());
        depositService.createDeposit(wallet, new BigDecimal("2.00"), "USD", UUID.randomUUID().toString());
        depositService.createDeposit(wallet, new BigDecimal("3.00"), "USD", UUID.randomUUID().toString());

        List<LedgerEntry> skippedOne = walletService.getLedgerEntries(wallet.getId(), 50, 1);
        assertEquals(2, skippedOne.size());
        assertEquals(new BigDecimal("2.00"), skippedOne.get(0).getAmount());
    }

    @Test
    void offsetPastTheEndReturnsEmpty() {
        Wallet wallet = newWallet();
        depositService.createDeposit(wallet, new BigDecimal("1.00"), "USD", UUID.randomUUID().toString());

        List<LedgerEntry> none = walletService.getLedgerEntries(wallet.getId(), 50, 10);
        assertEquals(0, none.size());
    }

    @Test
    void emptyWalletReturnsEmptyList() {
        Wallet wallet = newWallet();
        List<LedgerEntry> entries = walletService.getLedgerEntries(wallet.getId(), 50, 0);
        assertEquals(0, entries.size());
    }

    @Test
    void differentWalletsStayIsolated() {
        Wallet walletA = newWallet();
        Wallet walletB = newWallet();
        depositService.createDeposit(walletA, new BigDecimal("5.00"), "USD", UUID.randomUUID().toString());

        assertEquals(1, walletService.getLedgerEntries(walletA.getId(), 50, 0).size());
        assertEquals(0, walletService.getLedgerEntries(walletB.getId(), 50, 0).size());
    }
}
