package com.kevo.wallet.payment;

import java.math.BigDecimal;

public interface PaymentProvider {

    String getProviderName();

    PaymentResult processDeposit(BigDecimal amount, String currency, String idempotencyKey);

    PaymentResult processWithdrawal(BigDecimal amount, String currency, String idempotencyKey);

    record PaymentResult(boolean success, String providerReference, String failureReason) {}
}
