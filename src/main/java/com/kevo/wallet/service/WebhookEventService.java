package com.kevo.wallet.service;

import com.kevo.wallet.entity.WebhookEvent;
import com.kevo.wallet.repository.WebhookEventRepository;
import com.kevo.wallet.webhook.WebhookSignatureVerifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Set;

/**
 * The security boundary for payment-provider webhooks (spec Section 6). A
 * forged HTTP request pretending to be a payment provider must never be
 * able to move money. Every event goes through, in order: (1) timestamp
 * freshness and signature verification - is this really from the provider,
 * and is it recent, not a replay of an old request; (2) event-ID
 * deduplication, enforced by a database unique constraint so two identical
 * webhook deliveries can never both take effect; (3) event-type validation
 * against a known allow-list. Only after all three does anything in the
 * payload get acted on - and even then, the actual handler
 * (DepositReversalService) independently re-validates ownership and state
 * rather than trusting the payload.
 *
 * Deduplication is checked BEFORE attempting to insert (the common case
 * never touches the database's uniqueness constraint at all), and if two
 * requests for the exact same never-before-seen event genuinely race each
 * other, the loser's insert throws a real SQL constraint violation. That is
 * allowed to propagate rather than caught inline - a failed database
 * statement leaves the transaction unusable for anything further, no matter
 * whether the resulting Java exception is caught (the same lesson already
 * learned in WithdrawalService/DepositService/FundLockService) - so
 * recovering needs a fresh transaction, which is why findExisting() below
 * is a separate method the CONTROLLER calls after catching that exception.
 *
 * Known limitation, documented rather than silently accepted: once the
 * event is recorded, a failure in its own handler (dispatch()) does not
 * roll that record back - a retry of the same event ID is never reprocessed.
 * This favors "never double-process money" over "always eventually
 * process," the right tradeoff for a financial system, but worth a real
 * dead-letter/retry queue if a live provider is wired in.
 */
@Service
public class WebhookEventService {

    private static final Set<String> KNOWN_EVENT_TYPES = Set.of("deposit.reversed");
    private static final long TIMESTAMP_TOLERANCE_SECONDS = 300;

    public enum Status { ACCEPTED, DUPLICATE, REJECTED }
    public record WebhookOutcome(Status status, String message) {}

    private final WebhookSignatureVerifier signatureVerifier;
    private final WebhookEventRepository webhookEventRepository;
    private final DepositReversalService depositReversalService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebhookEventService(WebhookSignatureVerifier signatureVerifier,
                                WebhookEventRepository webhookEventRepository,
                                DepositReversalService depositReversalService) {
        this.signatureVerifier = signatureVerifier;
        this.webhookEventRepository = webhookEventRepository;
        this.depositReversalService = depositReversalService;
    }

    @Transactional
    public WebhookOutcome processEvent(String providerName, String rawBody, String signatureHeader, String timestampHeader) {

        if (!isFreshTimestamp(timestampHeader)) {
            return new WebhookOutcome(Status.REJECTED, "Timestamp missing, malformed, or outside the allowed window - possible replay");
        }

        if (!signatureVerifier.verify(rawBody, signatureHeader, timestampHeader)) {
            return new WebhookOutcome(Status.REJECTED, "Signature verification failed - request does not come from a trusted provider");
        }

        JsonNode payload;
        String eventId;
        String eventType;
        try {
            payload = objectMapper.readTree(rawBody);
            eventId = requireText(payload, "eventId");
            eventType = requireText(payload, "eventType");
        } catch (Exception e) {
            return new WebhookOutcome(Status.REJECTED, "Malformed webhook payload");
        }

        if (!KNOWN_EVENT_TYPES.contains(eventType)) {
            return new WebhookOutcome(Status.REJECTED, "Unrecognized event type: " + eventType);
        }

        if (webhookEventRepository.findByProviderNameAndEventId(providerName, eventId).isPresent()) {
            return new WebhookOutcome(Status.DUPLICATE, "Event " + eventId + " already processed - ignored, not reprocessed");
        }

        webhookEventRepository.save(new WebhookEvent(providerName, eventId, eventType, true));

        try {
            dispatch(eventType, payload);
        } catch (RuntimeException e) {
            // An application-level validation failure (e.g. the payload's
            // wallet/deposit don't actually match), not a database error -
            // safe to catch and still commit the event as recorded, so a
            // forged or permanently-mismatched retry isn't reprocessed
            // forever.
            return new WebhookOutcome(Status.REJECTED, "Event verified and recorded, but handling failed: " + e.getMessage());
        }

        return new WebhookOutcome(Status.ACCEPTED, "Event processed");
    }

    @Transactional(readOnly = true)
    public WebhookOutcome findExisting(String providerName, String eventId) {
        return webhookEventRepository.findByProviderNameAndEventId(providerName, eventId)
                .<WebhookOutcome>map(e -> new WebhookOutcome(Status.DUPLICATE,
                        "Event " + eventId + " already processed - ignored, not reprocessed"))
                .orElseGet(() -> new WebhookOutcome(Status.REJECTED,
                        "Event could not be processed and no existing record was found"));
    }

    private void dispatch(String eventType, JsonNode payload) {
        if ("deposit.reversed".equals(eventType)) {
            long kevoUserId = requireLong(payload, "kevoUserId");
            long depositId = requireLong(payload, "depositId");
            String reason = payload.has("reason") ? payload.get("reason").asText() : "Reversed by payment provider";
            depositReversalService.reverseDeposit(kevoUserId, depositId, reason);
        }
    }

    private boolean isFreshTimestamp(String timestampHeader) {
        try {
            long timestamp = Long.parseLong(timestampHeader);
            long now = Instant.now().getEpochSecond();
            return Math.abs(now - timestamp) <= TIMESTAMP_TOLERANCE_SECONDS;
        } catch (Exception e) {
            return false;
        }
    }

    private String requireText(JsonNode node, String field) {
        if (!node.hasNonNull(field)) throw new IllegalArgumentException("Missing field: " + field);
        return node.get(field).asText();
    }

    private long requireLong(JsonNode node, String field) {
        if (!node.hasNonNull(field)) throw new IllegalArgumentException("Missing field: " + field);
        return node.get(field).asLong();
    }
}
