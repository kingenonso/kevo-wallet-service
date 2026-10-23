package com.kevo.wallet.web;

import com.kevo.wallet.service.StripeWebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/webhooks")
public class StripeWebhookController {

    private final StripeWebhookService stripeWebhookService;

    public StripeWebhookController(StripeWebhookService stripeWebhookService) {
        this.stripeWebhookService = stripeWebhookService;
    }

    @PostMapping("/stripe")
    public ResponseEntity<Result> receiveWebhook(
            @RequestHeader("Stripe-Signature") String signature,
            @RequestBody String rawBody) {

        StripeWebhookService.Outcome outcome = stripeWebhookService.processEvent(rawBody, signature);

        Result result = new Result(outcome.status().name(), outcome.message());
        return switch (outcome.status()) {
            case ACCEPTED, DUPLICATE, IGNORED -> ResponseEntity.ok(result);
            case REJECTED -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
        };
    }

    public record Result(String status, String message) {}
}
