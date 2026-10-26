package com.kevo.wallet.service;

import com.kevo.wallet.entity.Deposit;
import com.kevo.wallet.entity.DepositStatus;
import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.entity.Withdrawal;
import com.kevo.wallet.entity.WithdrawalStatus;
import com.kevo.wallet.entity.WebhookEvent;
import com.kevo.wallet.repository.DepositRepository;
import com.kevo.wallet.repository.WalletRepository;
import com.kevo.wallet.repository.WebhookEventRepository;
import com.kevo.wallet.repository.WithdrawalRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Dispute;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Payout;
import com.stripe.model.Refund;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Verifies and processes real Stripe webhook deliveries for deposit
 * completion - the piece RealDepositService's own comment flags as "the
 * next piece of work." Deliberately separate from WebhookEventService/
 * PaymentProviderWebhookController, which is wired specifically for the
 * demo provider's own HMAC scheme (two headers, one known event type,
 * dispatching only to DepositReversalService) - Stripe's real signature
 * format (a single Stripe-Signature header) and event vocabulary don't fit
 * that pipeline, so this uses Stripe's own SDK for verification instead,
 * while still recording every event in the same WebhookEvent table (under
 * providerName "STRIPE") so duplicate deliveries are never double-processed,
 * matching the same safety guarantee the demo pipeline provides.
 *
 * Deposit reversal/reinstatement (2026-10-26): refunds and disputes are
 * handled here too, going straight to DepositReversalService rather than
 * through WebhookEventService's demo-only "deposit.reversed" event, since
 * Stripe's own event vocabulary doesn't match that pipeline's shape either.
 * Only definitive, money-actually-moved events trigger a reversal/
 * reinstatement - refund.created/refund.updated are both handled, but only
 * acted on once status is "succeeded" (KEVO's real deposits are ACH debits,
 * which commonly refund asynchronously - pending, then succeeded via a
 * later refund.updated - unlike a card, which usually completes
 * instantly), and only charge.dispute.funds_withdrawn (not
 * charge.dispute.created, which just opens a dispute - no money has
 * necessarily moved yet) triggers a reversal. charge.dispute.funds_reinstated
 * triggers the inverse. charge.dispute.created/closed/updated are
 * deliberately left IGNORED (falling through to the default case) - no
 * Dispute record is persisted anywhere in this codebase; a dispute's
 * lifecycle is tracked purely through its two money-movement events and
 * the ledger entries they produce.
 */
@Service
public class StripeWebhookService {

    private final String webhookSigningSecret;
    private final WebhookEventRepository webhookEventRepository;
    private final DepositRepository depositRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final WalletRepository walletRepository;
    private final LedgerPostingService ledgerPostingService;
    private final DepositReversalService depositReversalService;

    public StripeWebhookService(@Value("${stripe.webhook-signing-secret:}") String webhookSigningSecret,
                                 WebhookEventRepository webhookEventRepository,
                                 DepositRepository depositRepository,
                                 WithdrawalRepository withdrawalRepository,
                                 WalletRepository walletRepository,
                                 LedgerPostingService ledgerPostingService,
                                 DepositReversalService depositReversalService) {
        this.webhookSigningSecret = webhookSigningSecret;
        this.webhookEventRepository = webhookEventRepository;
        this.depositRepository = depositRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.walletRepository = walletRepository;
        this.ledgerPostingService = ledgerPostingService;
        this.depositReversalService = depositReversalService;
    }

    public enum Status { ACCEPTED, DUPLICATE, IGNORED, REJECTED }
    public record Outcome(Status status, String message) {}

    @Transactional
    public Outcome processEvent(String rawBody, String stripeSignatureHeader) {
        if (webhookSigningSecret == null || webhookSigningSecret.isBlank()) {
            return new Outcome(Status.REJECTED, "Stripe webhook signing secret is not configured");
        }

        Event event;
        try {
            event = Webhook.constructEvent(rawBody, stripeSignatureHeader, webhookSigningSecret);
        } catch (SignatureVerificationException e) {
            return new Outcome(Status.REJECTED, "Signature verification failed - request does not come from Stripe");
        }

        if (webhookEventRepository.findByProviderNameAndEventId("STRIPE", event.getId()).isPresent()) {
            return new Outcome(Status.DUPLICATE, "Event " + event.getId() + " already processed - ignored, not reprocessed");
        }
        webhookEventRepository.save(new WebhookEvent("STRIPE", event.getId(), event.getType(), true));

        return switch (event.getType()) {
            case "payment_intent.succeeded" -> handlePaymentIntentOutcome(event, true);
            case "payment_intent.payment_failed" -> handlePaymentIntentOutcome(event, false);
            case "payout.paid" -> handlePayoutOutcome(event, true);
            case "payout.failed" -> handlePayoutOutcome(event, false);
            case "refund.created", "refund.updated" -> handleRefundOutcome(event);
            case "charge.dispute.funds_withdrawn" -> handleDisputeOutcome(event, true);
            case "charge.dispute.funds_reinstated" -> handleDisputeOutcome(event, false);
            default -> new Outcome(Status.IGNORED, "Event type not handled: " + event.getType());
        };
    }

    private Outcome handlePayoutOutcome(Event event, boolean paid) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        StripeObject stripeObject;
        try {
            stripeObject = deserializer.deserializeUnsafe();
        } catch (com.stripe.exception.EventDataObjectDeserializationException e) {
            return new Outcome(Status.REJECTED, "Event verified and recorded, but payload could not be parsed as a Payout");
        }
        if (!(stripeObject instanceof Payout)) {
            return new Outcome(Status.REJECTED, "Event verified and recorded, but payload could not be parsed as a Payout");
        }
        Payout payout = (Payout) stripeObject;

        Optional<Withdrawal> withdrawalOpt = withdrawalRepository.findByProviderReference(payout.getId());
        if (withdrawalOpt.isEmpty()) {
            return new Outcome(Status.REJECTED, "Event verified and recorded, but no matching withdrawal was found for " + payout.getId());
        }
        Withdrawal withdrawal = withdrawalOpt.get();

        if (withdrawal.getStatus() != WithdrawalStatus.PENDING) {
            return new Outcome(Status.DUPLICATE, "Withdrawal " + withdrawal.getId() + " is already " + withdrawal.getStatus());
        }

        if (paid) {
            ledgerPostingService.completePendingWithdrawal(withdrawal);
        } else {
            String reason = payout.getFailureMessage() != null ? payout.getFailureMessage() : "Stripe reported this payout failed";
            ledgerPostingService.failPendingWithdrawal(withdrawal, reason);
        }
        return new Outcome(Status.ACCEPTED, "Withdrawal " + withdrawal.getId() + " marked " + (paid ? "COMPLETED" : "FAILED"));
    }

    private Outcome handlePaymentIntentOutcome(Event event, boolean succeeded) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        StripeObject stripeObject;
        try {
            stripeObject = deserializer.deserializeUnsafe();
        } catch (com.stripe.exception.EventDataObjectDeserializationException e) {
            return new Outcome(Status.REJECTED, "Event verified and recorded, but payload could not be parsed as a PaymentIntent");
        }
        if (!(stripeObject instanceof PaymentIntent)) {
            return new Outcome(Status.REJECTED, "Event verified and recorded, but payload could not be parsed as a PaymentIntent");
        }
        PaymentIntent intent = (PaymentIntent) stripeObject;

        Optional<Deposit> depositOpt = depositRepository.findByProviderReference(intent.getId());
        if (depositOpt.isEmpty()) {
            return new Outcome(Status.REJECTED, "Event verified and recorded, but no matching deposit was found for " + intent.getId());
        }
        Deposit deposit = depositOpt.get();

        if (deposit.getStatus() != DepositStatus.PENDING) {
            return new Outcome(Status.DUPLICATE, "Deposit " + deposit.getId() + " is already " + deposit.getStatus());
        }

        if (succeeded) {
            ledgerPostingService.completePendingDeposit(deposit);
        } else {
            ledgerPostingService.failPendingDeposit(deposit);
        }
        return new Outcome(Status.ACCEPTED, "Deposit " + deposit.getId() + " marked " + (succeeded ? "COMPLETED" : "FAILED"));
    }

    private Outcome handleRefundOutcome(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        StripeObject stripeObject;
        try {
            stripeObject = deserializer.deserializeUnsafe();
        } catch (com.stripe.exception.EventDataObjectDeserializationException e) {
            return new Outcome(Status.REJECTED, "Event verified and recorded, but payload could not be parsed as a Refund");
        }
        if (!(stripeObject instanceof Refund)) {
            return new Outcome(Status.REJECTED, "Event verified and recorded, but payload could not be parsed as a Refund");
        }
        Refund refund = (Refund) stripeObject;

        // Only "succeeded" means the money has actually left - a refund can
        // sit in pending/requires_action for a while, especially for ACH
        // debits (KEVO's real deposit method), which don't refund
        // instantly the way a card usually does. refund.updated is what
        // reports that later transition for a refund that didn't complete
        // synchronously on creation; refund.created is handled by this same
        // method in case it did.
        if (!"succeeded".equals(refund.getStatus())) {
            return new Outcome(Status.IGNORED, "Refund " + refund.getId() + " is not yet succeeded (status: " + refund.getStatus() + ") - not reversing yet");
        }

        String paymentIntentId = refund.getPaymentIntent();
        if (paymentIntentId == null) {
            return new Outcome(Status.REJECTED, "Event verified and recorded, but refund " + refund.getId() + " has no associated PaymentIntent");
        }
        return applyToDeposit(paymentIntentId, true, "Refunded via Stripe (refund " + refund.getId() + ")");
    }

    private Outcome handleDisputeOutcome(Event event, boolean fundsWithdrawn) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        StripeObject stripeObject;
        try {
            stripeObject = deserializer.deserializeUnsafe();
        } catch (com.stripe.exception.EventDataObjectDeserializationException e) {
            return new Outcome(Status.REJECTED, "Event verified and recorded, but payload could not be parsed as a Dispute");
        }
        if (!(stripeObject instanceof Dispute)) {
            return new Outcome(Status.REJECTED, "Event verified and recorded, but payload could not be parsed as a Dispute");
        }
        Dispute dispute = (Dispute) stripeObject;

        String paymentIntentId = dispute.getPaymentIntent();
        if (paymentIntentId == null) {
            return new Outcome(Status.REJECTED, "Event verified and recorded, but dispute " + dispute.getId() + " has no associated PaymentIntent");
        }
        String reason = fundsWithdrawn
                ? "Disputed via Stripe - funds withdrawn (dispute " + dispute.getId() + ")"
                : "Dispute resolved in KEVO's favor - funds reinstated (dispute " + dispute.getId() + ")";
        return applyToDeposit(paymentIntentId, fundsWithdrawn, reason);
    }

    // Shared by handleRefundOutcome/handleDisputeOutcome: finds the Deposit
    // behind a PaymentIntent and calls either reverseDeposit() (money left,
    // reverse=true) or reinstateDeposit() (money came back, reverse=false).
    // Both DepositReversalService calls are idempotent on their own terms
    // (already-REVERSED/already-COMPLETED is a no-op, not an error), and a
    // genuine validation/state failure (e.g. a dispute's funds_reinstated
    // arriving for a deposit that was never actually reversed) is caught
    // and recorded as REJECTED rather than left to retry forever against a
    // state that will never change - the same posture
    // WebhookEventService.processEvent() already takes around dispatch().
    private Outcome applyToDeposit(String paymentIntentId, boolean reverse, String reason) {
        Optional<Deposit> depositOpt = depositRepository.findByProviderReference(paymentIntentId);
        if (depositOpt.isEmpty()) {
            return new Outcome(Status.REJECTED, "Event verified and recorded, but no matching deposit was found for " + paymentIntentId);
        }
        Deposit deposit = depositOpt.get();

        Optional<Wallet> walletOpt = walletRepository.findById(deposit.getWalletId());
        if (walletOpt.isEmpty()) {
            return new Outcome(Status.REJECTED, "Event verified and recorded, but deposit " + deposit.getId() + "'s wallet no longer exists");
        }
        Long kevoUserId = walletOpt.get().getKevoUserId();

        try {
            if (reverse) {
                depositReversalService.reverseDeposit(kevoUserId, deposit.getId(), reason);
            } else {
                depositReversalService.reinstateDeposit(kevoUserId, deposit.getId(), reason);
            }
        } catch (RuntimeException e) {
            return new Outcome(Status.REJECTED, "Event verified and recorded, but " + (reverse ? "reversal" : "reinstatement") + " failed: " + e.getMessage());
        }
        return new Outcome(Status.ACCEPTED, "Deposit " + deposit.getId() + (reverse ? " reversed" : " reinstated"));
    }
}
