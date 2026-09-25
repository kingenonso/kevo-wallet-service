package com.kevo.wallet.service;

import com.kevo.wallet.entity.Bucket;
import com.kevo.wallet.entity.Direction;
import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.repository.LedgerEntryRepository;
import com.kevo.wallet.repository.WalletRepository;
import com.kevo.wallet.web.DepositController;
import com.kevo.wallet.web.FundLockController;
import com.kevo.wallet.web.FundLockRequest;
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

/**
 * Same concurrent-double-submit proof as WithdrawalConcurrencyTest, applied
 * to deposits and fund locks - both had the identical idempotency-check-
 * before-work pattern flagged in the M32 security review.
 */
@SpringBootTest
class DepositAndFundLockConcurrencyTest {

    @Autowired private WalletService walletService;
    @Autowired private DepositController depositController;
    @Autowired private FundLockController fundLockController;
    @Autowired private WalletRepository walletRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private Long testWalletId;

    @Test
    void concurrentDuplicateDepositRequestsMoveMoneyExactlyOnce() throws Exception {
        final long testKevoUserId = 910_000_000L + (System.currentTimeMillis() % 1_000_000);
        Wallet wallet = walletService.getOrCreateWallet(testKevoUserId);
        testWalletId = wallet.getId();

        String sharedIdempotencyKey = "concurrency-test-deposit-" + UUID.randomUUID();
        runConcurrently(() -> depositController.deposit(testKevoUserId,
                new DepositController.DepositRequest(new BigDecimal("100.00"), "USD", sharedIdempotencyKey)));

        long depositCreditCount = ledgerEntryRepository.findByWalletId(testWalletId).stream()
                .filter(e -> "DEPOSIT".equals(e.getEntryType()) && e.getDirection() == Direction.CREDIT)
                .count();
        BigDecimal available = walletService.computeBalances(testWalletId).get(Bucket.AVAILABLE);

        System.out.println("DEPOSIT ledger credit entries: " + depositCreditCount);
        System.out.println("Final available balance: " + available);

        assertEquals(1, depositCreditCount, "Expected exactly one DEPOSIT ledger entry.");
        assertEquals(new BigDecimal("100.00"), available, "Expected exactly $100 credited, not $200.");
    }

    @Test
    void concurrentDuplicateFundLockRequestsMoveMoneyExactlyOnce() throws Exception {
        final long testKevoUserId = 920_000_000L + (System.currentTimeMillis() % 1_000_000);
        Wallet wallet = walletService.getOrCreateWallet(testKevoUserId);
        testWalletId = wallet.getId();

        depositController.deposit(testKevoUserId,
                new DepositController.DepositRequest(new BigDecimal("100.00"), "USD",
                        "concurrency-test-fundlock-deposit-" + testKevoUserId));

        final long marketplaceTransactionId = 999_000_000L + (System.currentTimeMillis() % 1_000_000);
        String sharedIdempotencyKey = "concurrency-test-lock-" + UUID.randomUUID();
        runConcurrently(() -> fundLockController.createLock(testKevoUserId,
                new FundLockRequest(new BigDecimal("10.00"), "USD", marketplaceTransactionId, sharedIdempotencyKey)));

        var balances = walletService.computeBalances(testWalletId);
        BigDecimal available = balances.get(Bucket.AVAILABLE);
        BigDecimal locked = balances.get(Bucket.LOCKED);

        System.out.println("Final available balance: " + available);
        System.out.println("Final locked balance: " + locked);

        assertEquals(new BigDecimal("90.00"), available, "Expected exactly $10 moved out of available, not $20.");
        assertEquals(new BigDecimal("10.00"), locked, "Expected exactly $10 locked, not $20.");
    }

    private void runConcurrently(Runnable action) throws Exception {
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
                    action.run();
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
        assertEquals(0, errorCount.get(),
                "Expected BOTH concurrent requests to return cleanly - one doing the real work, "
                        + "the other gracefully recognizing it as an already-done duplicate.");
    }

    @AfterEach
    void cleanup() {
        if (testWalletId == null) return;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM fund_locks WHERE wallet_id = :id")
                    .setParameter("id", testWalletId).executeUpdate();
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
