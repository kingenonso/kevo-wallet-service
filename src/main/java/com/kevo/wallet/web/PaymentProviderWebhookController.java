package com.kevo.wallet.web;

import com.kevo.wallet.service.WebhookEventService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/**
 * Demo webhook receiver simulating a future real payment-provider webhook
 * (spec Section 6). No real provider sends anything here yet - this exists
 * so the verification pipeline itself is real, tested infrastructure ready
 * for when one is wired in, the same way DemoPaymentProvider stands in for
 * a future real payment provider.
 */
@RestController
@RequestMapping("/internal/webhooks")
public class PaymentProviderWebhookController {

    private final WebhookEventService webhookEventService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PaymentProviderWebhookController(WebhookEventService webhookEventService) {
        this.webhookEventService = webhookEventService;
    }

    @PostMapping("/demo-provider")
    public ResponseEntity<Result> receiveWebhook(
            @RequestHeader("X-Webhook-Signature") String signature,
            @RequestHeader("X-Webhook-Timestamp") String timestamp,
            @RequestBody String rawBody) {

        WebhookEventService.WebhookOutcome outcome;
        try {
            outcome = webhookEventService.processEvent("DEMO", rawBody, signature, timestamp);
        } catch (RuntimeException e) {
            // A genuinely concurrent duplicate delivery can hit the database's
            // own uniqueness constraint mid-transaction; recovering that needs
            // a fresh transaction, which findExisting() (a separate bean
            // method, invoked through the proxy) guarantees - same pattern as
            // WithdrawalService/DepositService/FundLockService.
            String eventId = extractEventIdOrNull(rawBody);
            outcome = eventId != null
                    ? webhookEventService.findExisting("DEMO", eventId)
                    : new WebhookEventService.WebhookOutcome(WebhookEventService.Status.REJECTED, "Unable to process event");
        }

        Result result = new Result(outcome.status().name(), outcome.message());
        return switch (outcome.status()) {
            case ACCEPTED, DUPLICATE -> ResponseEntity.ok(result);
            case REJECTED -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
        };
    }

    private String extractEventIdOrNull(String rawBody) {
        try {
            return objectMapper.readTree(rawBody).get("eventId").asText();
        } catch (Exception e) {
            return null;
        }
    }

    public record Result(String status, String message) {}
}
