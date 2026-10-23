package com.kevo.wallet.service;

import com.kevo.wallet.entity.BankAccount;
import com.kevo.wallet.entity.BankAccountStatus;
import com.kevo.wallet.entity.Deposit;
import com.kevo.wallet.entity.DepositStatus;
import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.repository.BankAccountRepository;
import com.kevo.wallet.repository.DepositRepository;
import com.kevo.wallet.repository.WalletRepository;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class RealDepositService {

    private final WalletRepository walletRepository;
    private final BankAccountRepository bankAccountRepository;
    private final DepositRepository depositRepository;
    private final StripeClient stripeClient;

    public RealDepositService(WalletRepository walletRepository,
                               BankAccountRepository bankAccountRepository,
                               DepositRepository depositRepository,
                               @Value("${stripe.secret-key}") String stripeSecretKey) {
        this.walletRepository = walletRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.depositRepository = depositRepository;
        this.stripeClient = new StripeClient(stripeSecretKey);
    }

    // Initiates a real ACH debit from the user's verified bank account, and
    // records it locally as a PENDING deposit right away. This does NOT
    // settle instantly - real ACH debits take a few business days to
    // actually clear - so KEVO's own wallet balance is NOT touched here.
    // The PENDING deposit created below only becomes COMPLETED (and the
    // balance only actually changes) once the Stripe webhook confirms the
    // money really cleared - see StripeWebhookService.
    //
    // idempotencyKey is checked against existing deposits BEFORE calling
    // Stripe (a retried/double-submitted request must never trigger a
    // second real ACH debit), and is also passed through to Stripe's own
    // idempotency key as defense in depth, mirroring
    // RealWithdrawalService's pattern.
    @Transactional
    public Deposit createRealDeposit(Long walletId, Long bankAccountId, long amountCents, String idempotencyKey) throws StripeException {

        var existing = depositRepository.findByWalletIdAndIdempotencyKey(walletId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));
        BankAccount bankAccount = bankAccountRepository.findByIdAndWalletId(bankAccountId, walletId)
                .orElseThrow(() -> new IllegalArgumentException("Bank account not found for this wallet"));
        if (bankAccount.getStatus() != BankAccountStatus.VERIFIED) {
            throw new IllegalStateException("Bank account is not verified");
        }
        if (bankAccount.getStripePaymentMethodId() == null) {
            throw new IllegalStateException("Bank account has no real payment method on file");
        }
        if (wallet.getStripeCustomerId() == null) {
            throw new IllegalStateException("Wallet has no Stripe customer on file");
        }

        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountCents)
                .setCurrency("usd")
                .setCustomer(wallet.getStripeCustomerId())
                .setPaymentMethod(bankAccount.getStripePaymentMethodId())
                .addPaymentMethodType("us_bank_account")
                .setConfirm(true)
                .setMandateData(
                        PaymentIntentCreateParams.MandateData.builder()
                                .setCustomerAcceptance(
                                        PaymentIntentCreateParams.MandateData.CustomerAcceptance.builder()
                                                .setType(PaymentIntentCreateParams.MandateData.CustomerAcceptance.Type.OFFLINE)
                                                .setOffline(
                                                        PaymentIntentCreateParams.MandateData.CustomerAcceptance.Offline.builder().build()
                                                )
                                                .build()
                                )
                                .build()
                )
                .build();

        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey("kevo_deposit_" + idempotencyKey)
                .build();
        PaymentIntent intent = stripeClient.paymentIntents().create(params, options);

        // amountCents was itself derived (exactly) from a 2-decimal BigDecimal
        // amount, so reconstructing it this way loses nothing.
        BigDecimal amount = BigDecimal.valueOf(amountCents, 2);
        Deposit deposit = new Deposit(walletId, idempotencyKey, intent.getId(), "STRIPE",
                amount, "usd", DepositStatus.PENDING, null);

        return saveOutcome(deposit, walletId, idempotencyKey);
    }

    @Transactional(readOnly = true)
    public Optional<Deposit> findExisting(Long walletId, String idempotencyKey) {
        return depositRepository.findByWalletIdAndIdempotencyKey(walletId, idempotencyKey);
    }

    private Deposit saveOutcome(Deposit deposit, Long walletId, String idempotencyKey) {
        try {
            return depositRepository.save(deposit);
        } catch (DataIntegrityViolationException e) {
            return depositRepository.findByWalletIdAndIdempotencyKey(walletId, idempotencyKey)
                    .orElseThrow(() -> e);
        }
    }
}
