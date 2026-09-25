package com.kevo.wallet.service;

import com.kevo.wallet.entity.BankAccount;
import com.kevo.wallet.entity.Bucket;
import com.kevo.wallet.entity.Direction;
import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.repository.BankAccountRepository;
import com.kevo.wallet.repository.LedgerEntryRepository;
import com.kevo.wallet.repository.WalletRepository;
import com.kevo.wallet.repository.WithdrawalRepository;
import com.kevo.wallet.web.WithdrawalController;
import com.kevo.wallet.web.WithdrawalRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class WithdrawalConcurrencyTest {

    @Autowired private WalletService walletService;
    @Autowired private DepositService depositService;
    @Autowired private BankAccountService bankAccountService;
    @Autowired private WithdrawalController withdrawalController;
    @Autowired private WalletRepository walletRepository;
    @Autowired private BankAccountRepository bankAccountRepository;
    @Autowired private WithdrawalRepository withdrawalRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private Long testWalletId;

    @Test
    void concurrentDuplicateWithdrawalRequestsMoveMoneyExactlyOnce() throws Exception {
        final long testKevoUserId = 900_000_000L + (System.currentTimeMillis() % 1_000_000);
        Wallet wallet = walletService.getOrCreateWallet(testKevoUserId);
        testWalletId = wallet.getId();

        depositService.createDeposit(wallet, new BigDecimal("100.00"), "USD",
                "concurrency-test-deposit-" + testKevoUserId);

        BankAccount account = bankAccountService.addBankAccount(
                testWalletId, "Concurrency Test", "Test Bank", "00001234567890");
        bankAccountService.verifyBankAccount(account.getId(), testWalletId);

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "UPDATE bank_accounts SET verified_at = now() - interval '25 hours' WHERE id = :id")
                        .setParameter("id", account.getId())
                        .executeUpdate());

        String sharedIdempotencyKey = "concurrency-test-" + UUID.randomUUID();
        int threadCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger errorCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    withdrawalController.createWithdrawal(testKevoUserId,
                            new WithdrawalRequest(new BigDecimal("10.00"), "USD", account.getId(), sharedIdempotencyKey));
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    errorCount.incrementAndGet();
                    System.out.println("Thread saw: " + t);
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(15, TimeUnit.SECONDS);

        System.out.println("Threads that returned normally: " + successCount.get());
        System.out.println("Threads that threw: " + errorCount.get());

        var withdrawalOpt = withdrawalRepository.findByWalletIdAndIdempotencyKey(testWalletId, sharedIdempotencyKey);
        System.out.println("Withdrawal row present: " + withdrawalOpt.isPresent());

        long ledgerDebitCount = ledgerEntryRepository.findByWalletId(testWalletId).stream()
                .filter(e -> "WITHDRAWAL".equals(e.getEntryType()) && e.getDirection() == Direction.DEBIT)
                .count();

        BigDecimal available = walletService.computeBalances(testWalletId).get(Bucket.AVAILABLE);

        System.out.println("WITHDRAWAL ledger debit entries: " + ledgerDebitCount);
        System.out.println("Final available balance: " + available);

        assertEquals(1, ledgerDebitCount,
                "Expected exactly one WITHDRAWAL ledger entry - more than one means the " +
                        "idempotency-before-lock race let a duplicate withdrawal through.");
        assertEquals(new BigDecimal("90.00"), available,
                "Expected exactly $10 debited from the $100 starting balance.");
        assertEquals(0, errorCount.get(),
                "Expected BOTH concurrent requests to return cleanly - one completing the withdrawal, "
                        + "the other gracefully recognizing it as an already-done duplicate.");
    }

    @AfterEach
    void cleanup() {
        if (testWalletId == null) return;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM deposits WHERE wallet_id = :id")
                    .setParameter("id", testWalletId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM withdrawals WHERE wallet_id = :id")
                    .setParameter("id", testWalletId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM ledger_entries WHERE wallet_id = :id")
                    .setParameter("id", testWalletId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM bank_accounts WHERE wallet_id = :id")
                    .setParameter("id", testWalletId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM wallets WHERE id = :id")
                    .setParameter("id", testWalletId).executeUpdate();
        });
    }
}
