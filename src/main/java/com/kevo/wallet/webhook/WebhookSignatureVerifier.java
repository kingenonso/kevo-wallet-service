package com.kevo.wallet.webhook;

/**
 * Abstraction over a payment provider's webhook signing scheme, mirroring
 * the PaymentProvider/DemoPaymentProvider pattern - swapping in a real
 * provider later means implementing this interface, not rewriting
 * WebhookEventService.
 */
public interface WebhookSignatureVerifier {
    boolean verify(String rawBody, String signatureHeader, String timestampHeader);
}
