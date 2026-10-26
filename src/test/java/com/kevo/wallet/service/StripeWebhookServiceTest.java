package com.kevo.wallet.service;

import com.kevo.wallet.entity.*;
import com.kevo.wallet.repository.DepositRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real end-to-end coverage of StripeWebhookService.processEvent() - signed,
 * realistic Stripe event payloads (Refund/Dispute JSON shapes taken
 * directly from Stripe's own API reference examples) go through the SAME
 * signature-verification path (Webhook.constructEvent, via a real HMAC-
 * SHA256 signature built the same way Stripe itself builds one) that a
 * genuine webhook delivery would use - not a mock of the Stripe SDK.
 *
 * This is also the first test coverage StripeWebhookService has had at
 * all (confirmed by its absence before this file - even the pre-existing
 * payment_intent/payout handling was previously untested), found while
 * adding the new refund/dispute wiring below, since exercising the new
 * cases meant exercising the whole class for the first time.
 *
 * Fixed test signing secret via @SpringBootTest properties - deliberately
 * NOT the real STRIPE_WEBHOOK_SIGNING_SECRET Eze configures for the real
 * endpoint, so this suite never depends on (or risks leaking) that value.
 */
@SpringBootTest(properties = {"stripe.webhook-signing-secret=whsec_test_signing_secret_for_tests_only"})
class StripeWebhookServiceTest {

    private static final String TEST_SIGNING_SECRET = "whsec_test_signing_secret_for_tests_only";

    @Autowired private WalletService walletService;
    @Autowired private LedgerPostingService ledgerPostingService;
    @Autowired private DepositReversalService depositReversalService;
    @Autowired private StripeWebhookService stripeWebhookService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private static final AtomicLong idCounter = new AtomicLong();
    private final List<Long> testWalletIds = new ArrayList<>();

    private Wallet newWallet() {
        long kevoUserId = 940_000_000L + (System.currentTimeMillis() % 1_000_000) + idCounter.incrementAndGet();
        Wallet wallet = walletService.getOrCreateWallet(kevoUserId);
        testWalletIds.add(wallet.getId());
        return wallet;
    }

    // A deposit that's already COMPLETED, with a real CREDIT ledger entry,
    // under a providerReference we control - standing in for a real
    // Stripe-backed ACH deposit whose webhook already confirmed it cleared.
    private Deposit completedStripeDeposit(Wallet wallet, String paymentIntentId, BigDecimal amount) {
        return ledgerPostingService.postCompletedDeposit(
                wallet, amount, "USD", "stripe-webhook-test-" + paymentIntentId, paymentIntentId, "STRIPE");
    }

    private String signature(long timestamp, String payload) {
        try {
            String signedPayload = timestamp + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TEST_SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return "t=" + timestamp + ",v1=" + hex;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private StripeWebhookService.Outcome send(String rawBody) {
        long timestamp = System.currentTimeMillis() / 1000;
        return stripeWebhookService.processEvent(rawBody, signature(timestamp, rawBody));
    }

    private String refundEvent(String eventId, String refundId, String paymentIntentId, String status) {
        return """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "2025-01-01",
                  "created": 1700000000,
                  "livemode": false,
                  "pending_webhooks": 1,
                  "request": {"id": null, "idempotency_key": null},
                  "type": "refund.created",
                  "data": {
                    "object": {
                      "id": "%s",
                      "object": "refund",
                      "amount": 50000,
                      "currency": "usd",
                      "charge": "ch_test_charge",
                      "payment_intent": "%s",
                      "status": "%s",
                      "reason": null,
                      "metadata": {}
                    }
                  }
                }
                """.formatted(eventId, refundId, paymentIntentId, status);
    }

    private String disputeEvent(String eventId, String disputeId, String paymentIntentId, String eventType, String disputeStatus) {
        return """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "2025-01-01",
                  "created": 1700000000,
                  "livemode": false,
                  "pending_webhooks": 1,
                  "request": {"id": null, "idempotency_key": null},
                  "type": "%s",
                  "data": {
                    "object": {
                      "id": "%s",
                      "object": "dispute",
                      "amount": 50000,
                      "currency": "usd",
                      "charge": "ch_test_charge",
                      "payment_intent": "%s",
                      "status": "%s",
                      "reason": "general",
                      "is_charge_refundable": true,
                      "livemode": false,
                      "metadata": {},
                      "balance_transactions": []
                    }
                  }
                }
                """.formatted(eventId, eventType, disputeId, paymentIntentId, disputeStatus);
    }

    @Test
    void succeededRefundReversesTheMatchingDeposit() {
        Wallet wallet = newWallet();
        String pi = "pi_test_refund_success_" + idCounter.get();
        completedStripeDeposit(wallet, pi, new BigDecimal("500.00"));

        var outcome = send(refundEvent("evt_refund_ok_" + idCounter.get(), "re_test_1", pi, "succeeded"));

        assertEquals(StripeWebhookService.Status.ACCEPTED, outcome.status());
        Deposit deposit = depositRepository.findByProviderReference(pi).orElseThrow();
        assertEquals(DepositStatus.REVERSED, deposit.getStatus());
        BigDecimal available = walletService.computeBalances(wallet.getId()).get(Bucket.AVAILABLE);
        assertEquals(0, BigDecimal.ZERO.compareTo(available));
    }

    @Test
    void pendingRefundIsIgnoredNotReversed() {
        Wallet wallet = newWallet();
        String pi = "pi_test_refund_pending_" + idCounter.get();
        completedStripeDeposit(wallet, pi, new BigDecimal("500.00"));

        var outcome = send(refundEvent("evt_refund_pending_" + idCounter.get(), "re_test_2", pi, "pending"));

        assertEquals(StripeWebhookService.Status.IGNORED, outcome.status());
        Deposit deposit = depositRepository.findByProviderReference(pi).orElseThrow();
        assertEquals(DepositStatus.COMPLETED, deposit.getStatus(), "A pending refund must not reverse the deposit yet");
    }

    @Test
    void laterRefundUpdatedSucceededReversesADepositThatWasStillPendingOnCreation() {
        // The real-world case this exists for: an ACH refund that starts
        // pending on refund.created and only later transitions via
        // refund.updated - KEVO must catch that second event, not just the
        // first.
        Wallet wallet = newWallet();
        String pi = "pi_test_refund_async_" + idCounter.get();
        completedStripeDeposit(wallet, pi, new BigDecimal("300.00"));

        var created = send(refundEvent("evt_refund_async_created_" + idCounter.get(), "re_test_3", pi, "pending"));
        assertEquals(StripeWebhookService.Status.IGNORED, created.status());

        String updatedBody = refundEvent("evt_refund_async_updated_" + idCounter.get(), "re_test_3", pi, "succeeded")
                .replace("\"type\": \"refund.created\"", "\"type\": \"refund.updated\"");
        var updated = send(updatedBody);

        assertEquals(StripeWebhookService.Status.ACCEPTED, updated.status());
        Deposit deposit = depositRepository.findByProviderReference(pi).orElseThrow();
        assertEquals(DepositStatus.REVERSED, deposit.getStatus());
    }

    @Test
    void disputeFundsWithdrawnReversesTheMatchingDeposit() {
        Wallet wallet = newWallet();
        String pi = "pi_test_dispute_withdrawn_" + idCounter.get();
        completedStripeDeposit(wallet, pi, new BigDecimal("250.00"));

        var outcome = send(disputeEvent("evt_dispute_withdrawn_" + idCounter.get(), "du_test_1", pi,
                "charge.dispute.funds_withdrawn", "lost"));

        assertEquals(StripeWebhookService.Status.ACCEPTED, outcome.status());
        Deposit deposit = depositRepository.findByProviderReference(pi).orElseThrow();
        assertEquals(DepositStatus.REVERSED, deposit.getStatus());
    }

    @Test
    void disputeFundsReinstatedReinstatesAPreviouslyReversedDeposit() {
        Wallet wallet = newWallet();
        String pi = "pi_test_dispute_reinstated_" + idCounter.get();
        Deposit deposit = completedStripeDeposit(wallet, pi, new BigDecimal("150.00"));
        depositReversalService.reverseDeposit(wallet.getKevoUserId(), deposit.getId(), "pre-reversed for test setup");

        var outcome = send(disputeEvent("evt_dispute_reinstated_" + idCounter.get(), "du_test_2", pi,
                "charge.dispute.funds_reinstated", "won"));

        assertEquals(StripeWebhookService.Status.ACCEPTED, outcome.status());
        Deposit reloaded = depositRepository.findByProviderReference(pi).orElseThrow();
        assertEquals(DepositStatus.COMPLETED, reloaded.getStatus());
        BigDecimal available = walletService.computeBalances(wallet.getId()).get(Bucket.AVAILABLE);
        assertEquals(0, new BigDecimal("150.00").compareTo(available));
    }

    @Test
    void disputeCreatedAloneIsIgnoredNotReversed() {
        // Only funds_withdrawn moves money - dispute.created just opens the
        // dispute and must not trigger a reversal on its own.
        Wallet wallet = newWallet();
        String pi = "pi_test_dispute_created_only_" + idCounter.get();
        completedStripeDeposit(wallet, pi, new BigDecimal("100.00"));

        var outcome = send(disputeEvent("evt_dispute_created_" + idCounter.get(), "du_test_3", pi,
                "charge.dispute.created", "warning_needs_response"));

        assertEquals(StripeWebhookService.Status.IGNORED, outcome.status());
        Deposit deposit = depositRepository.findByProviderReference(pi).orElseThrow();
        assertEquals(DepositStatus.COMPLETED, deposit.getStatus());
    }

    @Test
    void unknownPaymentIntentOnARefundIsRejectedNotSilentlyDropped() {
        var outcome = send(refundEvent("evt_refund_unknown_" + idCounter.get(), "re_test_unknown",
                "pi_does_not_exist_anywhere", "succeeded"));
        assertEquals(StripeWebhookService.Status.REJECTED, outcome.status());
    }

    @Test
    void duplicateRefundEventIsNotReprocessed() {
        Wallet wallet = newWallet();
        String pi = "pi_test_refund_dup_" + idCounter.get();
        completedStripeDeposit(wallet, pi, new BigDecimal("500.00"));
        String eventId = "evt_refund_dup_" + idCounter.get();
        String body = refundEvent(eventId, "re_test_dup", pi, "succeeded");

        var first = send(body);
        var second = send(body);

        assertEquals(StripeWebhookService.Status.ACCEPTED, first.status());
        assertEquals(StripeWebhookService.Status.DUPLICATE, second.status());
    }

    @Test
    void badSignatureIsRejected() {
        String body = refundEvent("evt_refund_badsig_" + idCounter.get(), "re_test_badsig", "pi_whatever", "succeeded");
        var outcome = stripeWebhookService.processEvent(body, "t=1700000000,v1=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
        assertEquals(StripeWebhookService.Status.REJECTED, outcome.status());
    }

    @AfterEach
    void cleanup() {
        if (testWalletIds.isEmpty()) return;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
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
        });
    }
}
