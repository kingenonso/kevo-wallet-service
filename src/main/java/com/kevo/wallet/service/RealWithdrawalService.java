package com.kevo.wallet.service;

import com.kevo.wallet.entity.*;
import com.kevo.wallet.repository.LedgerEntryRepository;
import com.kevo.wallet.repository.WalletRepository;
import com.kevo.wallet.repository.WithdrawalRepository;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Payout;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.PayoutCreateParams;
import com.stripe.param.TransferCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Real-money withdrawals via Stripe Connect (spec Section 14, the real
 * counterpart to WithdrawalService's demo/fictional path). A withdrawal
 * here does NOT go to one of the user's linked BankAccount rows the way
 * the demo path does - it pays out to the single bank account the user
 * added directly with Stripe during their own Connect Express onboarding
 * (see StripeConnectService), which KEVO never sees or stores.
 *
 * Money movement is two real Stripe calls: a Transfer moves funds from
 * KEVO's own platform balance into the user's connected account, then a
 * Payout pushes it from there out to their actual bank. Real payouts are
 * NOT instant - this records the withdrawal as PENDING and reserves the
 * funds in the WITHDRAWAL_PENDING bucket immediately, and only finalizes
 * it (via LedgerPostingService) once Stripe's own payout.paid/payout.failed
 * webhook confirms what actually happened - see StripeWebhookService.
 *
 * A withdrawal flagged HIGH risk is simply refused here rather than routed
 * into WithdrawalService's PENDING_REVIEW/admin-approval queue: that queue
 * resolves by calling the single shared PaymentProvider bean (the demo
 * provider), so a real withdrawal sent there would be wrongly processed as
 * a demo one. Building a provider-aware review queue is follow-up work,
 * not done here - a HIGH-risk real withdrawal fails outright for now with
 * a clear reason, rather than being silently mishandled.
 */
@Service
public class RealWithdrawalService {

    private static final BigDecimal DAILY_WITHDRAWAL_LIMIT = new BigDecimal("5000.00");
    static final String PROVIDER_NAME = "STRIPE_CONNECT";

    private final WithdrawalRepository withdrawalRepository;
    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final WalletService walletService;
    private final WithdrawalRiskService withdrawalRiskService;
    private final StripeClient stripeClient;

    public RealWithdrawalService(WithdrawalRepository withdrawalRepository,
                                  WalletRepository walletRepository,
                                  LedgerEntryRepository ledgerEntryRepository,
                                  WalletService walletService,
                                  WithdrawalRiskService withdrawalRiskService,
                                  @Value("${stripe.secret-key}") String stripeSecretKey) {
        this.withdrawalRepository = withdrawalRepository;
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.walletService = walletService;
        this.withdrawalRiskService = withdrawalRiskService;
        this.stripeClient = new StripeClient(stripeSecretKey);
    }

    @Transactional
    public Withdrawal createRealWithdrawal(Long walletId, BigDecimal amount, String currency, String idempotencyKey) {

        var existing = withdrawalRepository.findByWalletIdAndIdempotencyKey(walletId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        Wallet wallet = walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));

        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            return saveOutcome(failed(wallet.getId(), idempotencyKey, amount, currency,
                    "Wallet is restricted from withdrawals"), walletId, idempotencyKey);
        }

        String connectAccountId = wallet.getStripeConnectAccountId();
        if (connectAccountId == null || !wallet.isStripeConnectPayoutsEnabled()) {
            return saveOutcome(failed(wallet.getId(), idempotencyKey, amount, currency,
                    "Stripe payout onboarding is not complete yet - finish connecting a payout account first"),
                    walletId, idempotencyKey);
        }

        Map<Bucket, BigDecimal> balances = walletService.computeBalances(walletId);
        BigDecimal available = balances.getOrDefault(Bucket.AVAILABLE, BigDecimal.ZERO);
        if (available.compareTo(amount) < 0) {
            return saveOutcome(failed(wallet.getId(), idempotencyKey, amount, currency,
                    "Insufficient available funds"), walletId, idempotencyKey);
        }

        BigDecimal alreadyWithdrawnToday = withdrawalRepository.sumAmountByWalletIdAndStatusAndCreatedAtAfter(
                walletId, WithdrawalStatus.COMPLETED, LocalDateTime.now().minusHours(24));
        if (alreadyWithdrawnToday.add(amount).compareTo(DAILY_WITHDRAWAL_LIMIT) > 0) {
            return saveOutcome(failed(wallet.getId(), idempotencyKey, amount, currency,
                    "Daily withdrawal limit exceeded"), walletId, idempotencyKey);
        }

        WithdrawalRiskAssessment riskAssessment = withdrawalRiskService.assess(
                walletId, null, amount, DAILY_WITHDRAWAL_LIMIT);
        if (riskAssessment.requiresManualReview()) {
            return saveOutcome(failed(wallet.getId(), idempotencyKey, amount, currency,
                    "This withdrawal was flagged as " + riskAssessment.riskLevel()
                            + " risk (" + riskAssessment.reviewNotes() + ") and real payouts don't yet support manual review"
                            + " - try a smaller amount, or contact support"),
                    walletId, idempotencyKey);
        }

        long amountCents = amount.multiply(BigDecimal.valueOf(100)).longValueExact();

        Transfer transfer;
        try {
            TransferCreateParams transferParams = TransferCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency(currency.toLowerCase())
                    .setDestination(connectAccountId)
                    .build();
            RequestOptions transferOptions = RequestOptions.builder()
                    .setIdempotencyKey("kevo_transfer_" + idempotencyKey)
                    .build();
            transfer = stripeClient.transfers().create(transferParams, transferOptions);
        } catch (StripeException e) {
            return saveOutcome(failed(wallet.getId(), idempotencyKey, amount, currency,
                    "Stripe declined the transfer: " + e.getMessage()), walletId, idempotencyKey);
        }

        Payout payout;
        try {
            PayoutCreateParams payoutParams = PayoutCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency(currency.toLowerCase())
                    .build();
            RequestOptions payoutOptions = RequestOptions.builder()
                    .setStripeAccount(connectAccountId)
                    .setIdempotencyKey("kevo_payout_" + idempotencyKey)
                    .build();
            payout = stripeClient.payouts().create(payoutParams, payoutOptions);
        } catch (StripeException e) {
            // The transfer already moved funds into the connected account's
            // own Stripe balance - that part is NOT undone here. The money
            // isn't lost, it just hasn't reached their bank yet; it can be
            // paid out again by retrying, which is why this is recorded
            // under the transfer's own reference rather than silently
            // treated as if nothing happened.
            return saveOutcome(failed(wallet.getId(), idempotencyKey, amount, currency,
                    "Transfer " + transfer.getId() + " succeeded but the payout could not be created: "
                            + e.getMessage() + " - funds are held in the connected account"),
                    walletId, idempotencyKey);
        }

        String ledgerTransactionId = UUID.randomUUID().toString();

        LedgerEntry debitAvailable = new LedgerEntry(
                wallet.getId(), ledgerTransactionId, Direction.DEBIT, Bucket.AVAILABLE, "WITHDRAWAL",
                amount, currency, "Withdrawal via " + PROVIDER_NAME + " submitted", null, null);
        ledgerEntryRepository.save(debitAvailable);

        LedgerEntry creditPending = new LedgerEntry(
                wallet.getId(), ledgerTransactionId, Direction.CREDIT, Bucket.WITHDRAWAL_PENDING, "WITHDRAWAL",
                amount, currency, "Withdrawal via " + PROVIDER_NAME + " submitted", null, null);
        creditPending = ledgerEntryRepository.save(creditPending);

        Withdrawal withdrawal = new Withdrawal(wallet.getId(), idempotencyKey, payout.getId(), PROVIDER_NAME,
                amount, currency, null, "Stripe Connect payout to " + connectAccountId,
                WithdrawalStatus.PENDING, null, creditPending.getId());

        return saveOutcome(withdrawal, walletId, idempotencyKey);
    }

    @Transactional(readOnly = true)
    public Optional<Withdrawal> findExisting(Long walletId, String idempotencyKey) {
        return withdrawalRepository.findByWalletIdAndIdempotencyKey(walletId, idempotencyKey);
    }

    private Withdrawal failed(Long walletId, String idempotencyKey, BigDecimal amount, String currency, String reason) {
        return new Withdrawal(walletId, idempotencyKey, null, PROVIDER_NAME, amount, currency,
                null, "Stripe Connect payout", WithdrawalStatus.FAILED, reason, null);
    }

    private Withdrawal saveOutcome(Withdrawal withdrawal, Long walletId, String idempotencyKey) {
        try {
            return withdrawalRepository.save(withdrawal);
        } catch (DataIntegrityViolationException e) {
            return withdrawalRepository.findByWalletIdAndIdempotencyKey(walletId, idempotencyKey)
                    .orElseThrow(() -> e);
        }
    }
}
