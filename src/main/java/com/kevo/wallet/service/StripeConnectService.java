package com.kevo.wallet.service;

import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.repository.WalletRepository;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StripeConnectService {

    private final WalletRepository walletRepository;
    private final StripeClient stripeClient;

    public StripeConnectService(WalletRepository walletRepository,
                                 @Value("${stripe.secret-key}") String stripeSecretKey) {
        this.walletRepository = walletRepository;
        this.stripeClient = new StripeClient(stripeSecretKey);
    }

    // Creates the real Stripe Connect Express account this wallet will use
    // to receive payouts, the first time one is needed. Reuses it after
    // that instead of creating duplicates. "country" and "email" come from
    // the user's real KEVO profile, not typed by them here.
    @Transactional
    public String getOrCreateConnectAccount(Long walletId, String country, String email) throws StripeException {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));
        if (wallet.getStripeConnectAccountId() != null) {
            return wallet.getStripeConnectAccountId();
        }

        AccountCreateParams params = AccountCreateParams.builder()
                .setType(AccountCreateParams.Type.EXPRESS)
                .setCountry(country)
                .setEmail(email)
                .setCapabilities(
                        AccountCreateParams.Capabilities.builder()
                                .setCardPayments(
                                        AccountCreateParams.Capabilities.CardPayments.builder()
                                                .setRequested(true)
                                                .build()
                                )
                                .setTransfers(
                                        AccountCreateParams.Capabilities.Transfers.builder()
                                                .setRequested(true)
                                                .build()
                                )
                                .build()
                )
                .build();
        Account account = stripeClient.accounts().create(params);
        wallet.linkStripeConnectAccount(account.getId());
        walletRepository.save(wallet);
        return account.getId();
    }

    // Returns a one-time-use URL to Stripe's own hosted onboarding form,
    // where the user enters their real identity/KYC details directly with
    // Stripe - KEVO never sees or stores that information itself.
    public String createOnboardingLink(String stripeConnectAccountId, String refreshUrl, String returnUrl) throws StripeException {
        AccountLinkCreateParams params = AccountLinkCreateParams.builder()
                .setAccount(stripeConnectAccountId)
                .setRefreshUrl(refreshUrl)
                .setReturnUrl(returnUrl)
                .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                .build();
        AccountLink link = stripeClient.accountLinks().create(params);
        return link.getUrl();
    }

    // Asks Stripe directly whether this user has actually finished
    // onboarding and can receive real payouts yet, and records that fact
    // locally once Stripe confirms it. Never trust a client-side claim of
    // "done" - always re-check with Stripe itself.
    @Transactional
    public boolean refreshPayoutsEnabled(Long walletId) throws StripeException {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));
        if (wallet.getStripeConnectAccountId() == null) {
            return false;
        }
        Account account = stripeClient.accounts().retrieve(wallet.getStripeConnectAccountId());
        if (Boolean.TRUE.equals(account.getPayoutsEnabled())) {
            wallet.markStripeConnectPayoutsEnabled();
            walletRepository.save(wallet);
        }
        return wallet.isStripeConnectPayoutsEnabled();
    }
}
