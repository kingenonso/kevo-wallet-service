package com.kevo.wallet.service;

import com.kevo.wallet.entity.Deposit;
import com.kevo.wallet.entity.DepositStatus;
import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.webhook.DemoWebhookSignatureVerifier;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit coverage for the webhook security pipeline (spec Section 6):
 * signature verification, timestamp/replay protection, event-ID
 * deduplication, and event-type validation - all enforced BEFORE anything
 * in the payload is trusted, and the actual dispatched action (deposit
 * reversal) still independently re-validates ownership itself.
 */
@SpringBootTest
class WebhookEventServiceTest {

    @Autowired private WalletService walletService;
    @Autowired private DepositService depositService;
    @Autowired private WebhookEventService webhookEventService;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    @Value("${kevo.webhook-secret}")
    private String webhookSecret;

    private static final AtomicLong idCounter = new AtomicLong();
    private final List<Long> testWalletIds = new ArrayList<>();

    private Wallet newFundedWallet(BigDecimal amount, String depositKey) {
        long kevoUserId = 950_000_000L + (System.currentTimeMillis() % 1_000_000) + idCounter.incrementAndGet();
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);
        testWalletIds.add(wallet.getId());
        depositService.createDeposit(wallet, amount, "USD", depositKey);
        return wallet;
    }

    private String sign(String timestamp, String body) throws Exception {
        return DemoWebhookSignatureVerifier.sign(timestamp + "." + body, webhookSecret);
    }

    @Test
    void aProperlySignedFreshEventReversesTheDeposit() throws Exception {
        Wallet wallet = newFundedWallet(new BigDecimal("300.00"), "webhook-accept-deposit-1");
        Deposit deposit = depositService.findExisting(wallet.getId(), "webhook-accept-deposit-1").orElseThrow();

        String body = "{\"eventId\":\"evt-accept-1\",\"eventType\":\"deposit.reversed\","
                + "\"kevoUserId\":" + wallet.getKevoUserId() + ",\"depositId\":" + deposit.getId()
                + ",\"reason\":\"test chargeback\"}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = sign(timestamp, body);

        WebhookEventService.WebhookOutcome outcome = webhookEventService.processEvent("DEMO", body, signature, timestamp);

        assertEquals(WebhookEventService.Status.ACCEPTED, outcome.status());
        Deposit reloaded = depositService.findExisting(wallet.getId(), "webhook-accept-deposit-1").orElseThrow();
        assertEquals(DepositStatus.REVERSED, reloaded.getStatus());
    }

    @Test
    void theSameEventIdSubmittedTwiceIsProcessedOnlyOnce() throws Exception {
        Wallet wallet = newFundedWallet(new BigDecimal("300.00"), "webhook-dup-deposit-1");
        Deposit deposit = depositService.findExisting(wallet.getId(), "webhook-dup-deposit-1").orElseThrow();

        String body = "{\"eventId\":\"evt-dup-1\",\"eventType\":\"deposit.reversed\","
                + "\"kevoUserId\":" + wallet.getKevoUserId() + ",\"depositId\":" + deposit.getId()
                + ",\"reason\":\"test chargeback\"}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = sign(timestamp, body);

        WebhookEventService.WebhookOutcome first = webhookEventService.processEvent("DEMO", body, signature, timestamp);
        WebhookEventService.WebhookOutcome second = webhookEventService.processEvent("DEMO", body, signature, timestamp);

        assertEquals(WebhookEventService.Status.ACCEPTED, first.status());
        assertEquals(WebhookEventService.Status.DUPLICATE, second.status());
    }

    @Test
    void aBadSignatureIsRejectedAndNothingIsProcessed() {
        Wallet wallet = newFundedWallet(new BigDecimal("300.00"), "webhook-badsig-deposit-1");
        Deposit deposit = depositService.findExisting(wallet.getId(), "webhook-badsig-deposit-1").orElseThrow();

        String body = "{\"eventId\":\"evt-badsig-1\",\"eventType\":\"deposit.reversed\","
                + "\"kevoUserId\":" + wallet.getKevoUserId() + ",\"depositId\":" + deposit.getId() + "}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        WebhookEventService.WebhookOutcome outcome =
                webhookEventService.processEvent("DEMO", body, "not-a-real-signature", timestamp);

        assertEquals(WebhookEventService.Status.REJECTED, outcome.status());
        Deposit reloaded = depositService.findExisting(wallet.getId(), "webhook-badsig-deposit-1").orElseThrow();
        assertEquals(DepositStatus.COMPLETED, reloaded.getStatus());
    }

    @Test
    void aStaleTimestampIsRejectedEvenWithAValidSignature() throws Exception {
        String body = "{\"eventId\":\"evt-stale-1\",\"eventType\":\"deposit.reversed\",\"kevoUserId\":1,\"depositId\":1}";
        String staleTimestamp = String.valueOf(Instant.now().getEpochSecond() - 3600);
        String signature = sign(staleTimestamp, body);

        WebhookEventService.WebhookOutcome outcome = webhookEventService.processEvent("DEMO", body, signature, staleTimestamp);

        assertEquals(WebhookEventService.Status.REJECTED, outcome.status());
    }

    @Test
    void anUnrecognizedEventTypeIsRejected() throws Exception {
        String body = "{\"eventId\":\"evt-unknown-1\",\"eventType\":\"totally.unknown\"}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = sign(timestamp, body);

        WebhookEventService.WebhookOutcome outcome = webhookEventService.processEvent("DEMO", body, signature, timestamp);

        assertEquals(WebhookEventService.Status.REJECTED, outcome.status());
    }

    @AfterEach
    void cleanup() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM webhook_events WHERE event_id LIKE 'evt-%'").executeUpdate();
            if (!testWalletIds.isEmpty()) {
                entityManager.createNativeQuery("DELETE FROM withdrawals WHERE wallet_id IN (:ids)")
                        .setParameter("ids", testWalletIds).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM deposits WHERE wallet_id IN (:ids)")
                        .setParameter("ids", testWalletIds).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM ledger_entries WHERE wallet_id IN (:ids)")
                        .setParameter("ids", testWalletIds).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM bank_accounts WHERE wallet_id IN (:ids)")
                        .setParameter("ids", testWalletIds).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM wallets WHERE id IN (:ids)")
                        .setParameter("ids", testWalletIds).executeUpdate();
            }
        });
    }
}
