package com.kevo.wallet.service;

import com.kevo.wallet.entity.Deposit;
import com.kevo.wallet.entity.DepositStatus;
import com.kevo.wallet.entity.WebhookEvent;
import com.kevo.wallet.repository.DepositRepository;
import com.kevo.wallet.repository.WebhookEventRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
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
 */
@Service
public class StripeWebhookService {

    private final String webhookSigningSecret;
    private final WebhookEventRepository webhookEventRepository;
    private final DepositRepository depositRepository;
    private final LedgerPostingService ledgerPostingService;

    public StripeWebhookService(@Value("${stripe.webhook-signing-secret:}") String webhookSigningSecret,
                                 WebhookEventRepository webhookEventRepository,
                                 DepositRepository depositRepository,
                                 LedgerPostingService ledgerPostingService) {
        this.webhookSigningSecret = webhookSigningSecret;
        this.webhookEventRepository = webhookEventRepository;
        this.depositRepository = depositRepository;
        this.ledgerPostingService = ledgerPostingService;
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
            default -> new Outcome(Status.IGNORED, "Event type not handled: " + event.getType());
        };
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
}
