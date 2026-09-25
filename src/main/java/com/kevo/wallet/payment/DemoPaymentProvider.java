package com.kevo.wallet.payment;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class DemoPaymentProvider implements PaymentProvider {

    @Override
    public String getProviderName() {
        return "DEMO";
    }

    @Override
    public PaymentResult processDeposit(BigDecimal amount, String currency, String idempotencyKey) {
        String providerReference = "demo_" + UUID.randomUUID();
        return new PaymentResult(true, providerReference, null);
    }

    @Override
    public PaymentResult processWithdrawal(BigDecimal amount, String currency, String idempotencyKey) {
        // Demo/prototype only: simulates an instant successful bank transfer.
        // Do NOT connect to real banking infrastructure (spec Section 13).
        String providerReference = "demo_wd_" + UUID.randomUUID();
        return new PaymentResult(true, providerReference, null);
    }
}
