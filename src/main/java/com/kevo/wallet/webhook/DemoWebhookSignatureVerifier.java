package com.kevo.wallet.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Demo/prototype stand-in for a real provider's webhook signing scheme
 * (e.g. an HMAC-SHA256 signature over "timestamp.body", the same shape
 * Stripe and similar providers use). Whatever real provider eventually
 * gets wired in will have its own signature format; WebhookEventService
 * depends only on the WebhookSignatureVerifier interface, so swapping
 * this out later doesn't require touching the verification pipeline.
 */
@Component
public class DemoWebhookSignatureVerifier implements WebhookSignatureVerifier {

    @Value("${kevo.webhook-secret}")
    private String webhookSecret;

    @Override
    public boolean verify(String rawBody, String signatureHeader, String timestampHeader) {
        if (signatureHeader == null || timestampHeader == null) return false;
        try {
            String expected = sign(timestampHeader + "." + rawBody, webhookSecret);
            return constantTimeEquals(expected, signatureHeader);
        } catch (Exception e) {
            return false;
        }
    }

    public static String sign(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : rawHmac) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // Same constant-time comparison discipline as InternalServiceAuthFilter -
    // a signature check must never leak timing information about how much of
    // a guess was correct.
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        if (aBytes.length != bBytes.length) return false;
        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }
        return result == 0;
    }
}
