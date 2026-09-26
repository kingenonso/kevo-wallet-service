package com.kevo.wallet.service;

import com.kevo.wallet.entity.*;
import com.kevo.wallet.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Architecture for detecting financial inconsistencies across the ledger,
 * deposits, withdrawals, and fund locks (spec Section 24). This NEVER
 * modifies any financial record - it only reports what it finds, so a real
 * inconsistency fails loudly and safely instead of being silently "fixed."
 *
 * Every real money-moving operation in this codebase groups its ledger
 * entries under one ledger_transaction_id: a deposit is a single CREDIT
 * entry (money entering from outside KEVO); a withdrawal is a single DEBIT
 * entry (money leaving); a lock/release/capture is exactly two entries in
 * the same group with equal amounts and opposite directions (money moving
 * between buckets, or between two wallets for a capture); a deposit reversal
 * adds a second, DEBIT entry to the original deposit's group. This service
 * checks that every group in the ledger actually matches one of those known
 * shapes, and separately checks that every COMPLETED/CAPTURED/RELEASED
 * record has the ledger entry it claims to have.
 */
@Service
public class ReconciliationService {

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final DepositRepository depositRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final FundLockRepository fundLockRepository;

    public ReconciliationService(WalletRepository walletRepository,
                                  LedgerEntryRepository ledgerEntryRepository,
                                  DepositRepository depositRepository,
                                  WithdrawalRepository withdrawalRepository,
                                  FundLockRepository fundLockRepository) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.depositRepository = depositRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.fundLockRepository = fundLockRepository;
    }

    public record Finding(String category, String description) {}
    public record ReconciliationReport(Long walletId, boolean clean, List<Finding> findings) {}

    @Transactional(readOnly = true)
    public ReconciliationReport reconcileWallet(Long walletId) {
        List<Finding> findings = new ArrayList<>();

        walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));

        List<LedgerEntry> entries = ledgerEntryRepository.findByWalletId(walletId);
        Map<String, List<LedgerEntry>> byTransaction = entries.stream()
                .collect(Collectors.groupingBy(LedgerEntry::getLedgerTransactionId));

        for (var group : byTransaction.entrySet()) {
            findings.addAll(checkTransactionGroup(group.getKey(), group.getValue()));
        }

        findings.addAll(checkOrphanedRecords(walletId));

        return new ReconciliationReport(walletId, findings.isEmpty(), findings);
    }

    private List<Finding> checkTransactionGroup(String ledgerTransactionId, List<LedgerEntry> group) {
        List<Finding> findings = new ArrayList<>();
        Set<String> entryTypes = group.stream().map(LedgerEntry::getEntryType).collect(Collectors.toSet());

        // DEPOSIT/WITHDRAWAL are single-sided (money crossing KEVO's boundary).
        // CAPTURE is ALSO single-sided from any one wallet's point of view - its
        // two real entries (buyer's debit, seller's credit) deliberately live on
        // two DIFFERENT wallets, so only one of them ever shows up in this
        // wallet's own ledger; the other side is checked separately, globally,
        // by checkCaptureIntegrity below.
        boolean singleSided = entryTypes.equals(Set.of("DEPOSIT")) || entryTypes.equals(Set.of("WITHDRAWAL"));
        boolean reversedDeposit = entryTypes.equals(Set.of("DEPOSIT", "DEPOSIT_REVERSAL"));
        boolean pairedWithinWallet = entryTypes.equals(Set.of("LOCK")) || entryTypes.equals(Set.of("RELEASE"));
        boolean crossWalletCapture = entryTypes.equals(Set.of("CAPTURE"));

        if (singleSided) {
            if (group.size() != 1) {
                findings.add(new Finding("DUPLICATED_ENTRY",
                        "Ledger transaction " + ledgerTransactionId + " is a " + entryTypes
                                + " but has " + group.size() + " entries instead of exactly 1"));
            }
            return findings;
        }

        if (reversedDeposit) {
            if (group.size() != 2) {
                findings.add(new Finding("BALANCE_INCONSISTENCY",
                        "Deposit reversal group " + ledgerTransactionId + " has " + group.size()
                                + " entries instead of exactly 2 (original + reversal)"));
                return findings;
            }
            findings.addAll(checkOffsettingPair(ledgerTransactionId, group));
            return findings;
        }

        if (pairedWithinWallet) {
            if (group.size() != 2) {
                findings.add(new Finding("MISSING_LEDGER_ENTRY",
                        "Ledger transaction " + ledgerTransactionId + " (" + entryTypes
                                + ") has " + group.size() + " entries instead of exactly 2 - "
                                + "a lock/release must always move funds in and out together within one wallet"));
                return findings;
            }
            findings.addAll(checkOffsettingPair(ledgerTransactionId, group));
            return findings;
        }

        if (crossWalletCapture) {
            if (group.size() != 1) {
                findings.add(new Finding("DUPLICATED_ENTRY",
                        "Ledger transaction " + ledgerTransactionId + " (CAPTURE) has " + group.size()
                                + " entries within this wallet instead of exactly 1 - a capture should only touch each wallet once"));
                return findings;
            }
            findings.addAll(checkCaptureIntegrity(ledgerTransactionId));
            return findings;
        }

        findings.add(new Finding("UNEXPECTED_LEDGER_GROUP",
                "Ledger transaction " + ledgerTransactionId + " has an unrecognized entry-type combination "
                        + entryTypes + " (" + group.size() + " entries) - does not match any known money-movement shape"));
        return findings;
    }

    private List<Finding> checkCaptureIntegrity(String ledgerTransactionId) {
        List<Finding> findings = new ArrayList<>();
        List<LedgerEntry> globalGroup = ledgerEntryRepository.findByLedgerTransactionId(ledgerTransactionId);

        if (globalGroup.size() != 2) {
            findings.add(new Finding("MISSING_LEDGER_ENTRY",
                    "Capture " + ledgerTransactionId + " has " + globalGroup.size()
                            + " entries across all wallets instead of exactly 2 (one buyer debit, one seller credit)"));
            return findings;
        }

        LedgerEntry a = globalGroup.get(0);
        LedgerEntry b = globalGroup.get(1);

        if (a.getWalletId().equals(b.getWalletId())) {
            findings.add(new Finding("BALANCE_INCONSISTENCY",
                    "Capture " + ledgerTransactionId + " has both entries on the same wallet (" + a.getWalletId()
                            + ") - a capture must move funds between two different wallets"));
        }
        findings.addAll(checkOffsettingPair(ledgerTransactionId, globalGroup));
        return findings;
    }

    private List<Finding> checkOffsettingPair(String ledgerTransactionId, List<LedgerEntry> pair) {
        List<Finding> findings = new ArrayList<>();
        LedgerEntry a = pair.get(0);
        LedgerEntry b = pair.get(1);

        if (a.getDirection() == b.getDirection()) {
            findings.add(new Finding("BALANCE_INCONSISTENCY",
                    "Ledger transaction " + ledgerTransactionId + " has two entries with the same direction ("
                            + a.getDirection() + ") - a paired movement must have one DEBIT and one CREDIT"));
        }
        if (a.getAmount().compareTo(b.getAmount()) != 0) {
            findings.add(new Finding("BALANCE_INCONSISTENCY",
                    "Ledger transaction " + ledgerTransactionId + " has mismatched amounts ("
                            + a.getAmount() + " vs " + b.getAmount() + ") between its two entries"));
        }
        if (!a.getCurrency().equalsIgnoreCase(b.getCurrency())) {
            findings.add(new Finding("BALANCE_INCONSISTENCY",
                    "Ledger transaction " + ledgerTransactionId + " has mismatched currencies ("
                            + a.getCurrency() + " vs " + b.getCurrency() + ") between its two entries"));
        }
        return findings;
    }

    private List<Finding> checkOrphanedRecords(Long walletId) {
        List<Finding> findings = new ArrayList<>();

        for (Deposit deposit : depositRepository.findAll()) {
            if (!deposit.getWalletId().equals(walletId)) continue;
            if ((deposit.getStatus() == DepositStatus.COMPLETED || deposit.getStatus() == DepositStatus.REVERSED)
                    && deposit.getLedgerEntryId() == null) {
                findings.add(new Finding("ORPHANED_RECORD",
                        "Deposit " + deposit.getId() + " is " + deposit.getStatus() + " but has no ledger entry recorded"));
            }
        }

        for (Withdrawal withdrawal : withdrawalRepository.findAll()) {
            if (!withdrawal.getWalletId().equals(walletId)) continue;
            if (withdrawal.getStatus() == WithdrawalStatus.COMPLETED && withdrawal.getLedgerEntryId() == null) {
                findings.add(new Finding("ORPHANED_RECORD",
                        "Withdrawal " + withdrawal.getId() + " is COMPLETED but has no ledger entry recorded"));
            }
        }

        for (FundLock lock : fundLockRepository.findAll()) {
            if (!lock.getWalletId().equals(walletId)) continue;
            if (lock.getLockLedgerEntryId() == null) {
                findings.add(new Finding("ORPHANED_RECORD",
                        "Fund lock " + lock.getId() + " has no lock ledger entry recorded"));
            }
            if (lock.getStatus() == FundLockStatus.RELEASED && lock.getReleaseLedgerEntryId() == null) {
                findings.add(new Finding("ORPHANED_RECORD",
                        "Fund lock " + lock.getId() + " is RELEASED but has no release ledger entry recorded"));
            }
            if (lock.getStatus() == FundLockStatus.CAPTURED && lock.getCaptureLedgerEntryId() == null) {
                findings.add(new Finding("ORPHANED_RECORD",
                        "Fund lock " + lock.getId() + " is CAPTURED but has no capture ledger entry recorded"));
            }
        }

        return findings;
    }

    @Transactional(readOnly = true)
    public List<ReconciliationReport> reconcileAllWallets() {
        return walletRepository.findAll().stream()
                .map(w -> reconcileWallet(w.getId()))
                .filter(r -> !r.clean())
                .toList();
    }
}
