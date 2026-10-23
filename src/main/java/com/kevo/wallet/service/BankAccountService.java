package com.kevo.wallet.service;

import com.kevo.wallet.entity.BankAccount;
import com.kevo.wallet.entity.BankAccountStatus;
import com.kevo.wallet.entity.Wallet;
import com.kevo.wallet.repository.BankAccountRepository;
import com.kevo.wallet.repository.WalletRepository;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.financialconnections.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.model.PaymentMethod;
import com.stripe.param.PaymentMethodAttachParams;
import com.stripe.param.PaymentMethodCreateParams;
import com.stripe.param.financialconnections.SessionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BankAccountService {

    private final BankAccountRepository bankAccountRepository;
    private final WalletRepository walletRepository;
    private final StripeClient stripeClient;

    public BankAccountService(BankAccountRepository bankAccountRepository,
                               WalletRepository walletRepository,
                               @Value("${stripe.secret-key}") String stripeSecretKey) {
        this.bankAccountRepository = bankAccountRepository;
        this.walletRepository = walletRepository;
        this.stripeClient = new StripeClient(stripeSecretKey);
    }

    @Transactional
    public BankAccount addBankAccount(Long walletId, String accountHolderName, String bankName, String accountNumber) {
        if (accountNumber == null || accountNumber.replaceAll("\\D", "").length() < 4) {
            throw new IllegalArgumentException("Account number is invalid");
        }
        String digitsOnly = accountNumber.replaceAll("\\D", "");
        String lastFour = digitsOnly.substring(digitsOnly.length() - 4);

        // A retried "add bank account" request (e.g. a network retry) would
        // otherwise create a second row for the same real account. Treat an
        // existing, non-disabled account with the same bank + last four as
        // the same submission rather than creating a duplicate.
        Optional<BankAccount> existing = bankAccountRepository.findByWalletId(walletId).stream()
                .filter(a -> a.getStatus() != BankAccountStatus.DISABLED)
                .filter(a -> a.getBankName().equals(bankName) && a.getLastFour().equals(lastFour))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }

        // Demo stand-in for a real tokenization call to a banking/payment provider.
        // The real account number is never persisted anywhere past this method.
        String providerReference = "DEMO-BANK-" + UUID.randomUUID();

        BankAccount account = new BankAccount(walletId, accountHolderName, bankName, lastFour, providerReference);
        return bankAccountRepository.save(account);
    }

    public List<BankAccount> listBankAccounts(Long walletId) {
        return bankAccountRepository.findByWalletId(walletId);
    }

    @Transactional
    public BankAccount verifyBankAccount(Long bankAccountId, Long walletId) {
        BankAccount account = requireOwnedAccount(bankAccountId, walletId);
        if (account.getStatus() == BankAccountStatus.DISABLED) {
            throw new IllegalStateException("Cannot verify a disabled bank account");
        }
        account.markVerified();
        return bankAccountRepository.save(account);
    }

    @Transactional
    public BankAccount disableBankAccount(Long bankAccountId, Long walletId) {
        BankAccount account = requireOwnedAccount(bankAccountId, walletId);
        account.markDisabled();
        return bankAccountRepository.save(account);
    }

    private BankAccount requireOwnedAccount(Long bankAccountId, Long walletId) {
        return bankAccountRepository.findByIdAndWalletId(bankAccountId, walletId)
                .orElseThrow(() -> new IllegalArgumentException("Bank account not found for this wallet"));
    }

    // Stripe requires one real Customer object per person before it will link
    // a real bank account for them. Created once per wallet and reused.
    private String getOrCreateStripeCustomerId(Long walletId, String accountHolderName) throws StripeException {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));
        if (wallet.getStripeCustomerId() != null) {
            return wallet.getStripeCustomerId();
        }
        CustomerCreateParams params = CustomerCreateParams.builder()
                .setName(accountHolderName)
                .build();
        Customer customer = stripeClient.customers().create(params);
        wallet.linkStripeCustomer(customer.getId());
        walletRepository.save(wallet);
        return customer.getId();
    }

    // Starts a real Stripe Financial Connections session - the frontend uses
    // the returned client secret to launch Stripe's own secure bank-login
    // modal. Nothing is verified yet at this point.
    @Transactional
    public String createBankConnectionSession(Long walletId, String accountHolderName) {
        try {
            String customerId = getOrCreateStripeCustomerId(walletId, accountHolderName);
            SessionCreateParams params = SessionCreateParams.builder()
                    .setAccountHolder(
                            SessionCreateParams.AccountHolder.builder()
                                    .setType(SessionCreateParams.AccountHolder.Type.CUSTOMER)
                                    .setCustomer(customerId)
                                    .build()
                    )
                    .addPermission(SessionCreateParams.Permission.PAYMENT_METHOD)
                    .build();
            Session session = stripeClient.financialConnections().sessions().create(params);
            return session.getClientSecret();
        } catch (StripeException e) {
            throw new IllegalStateException("Could not start bank connection: " + e.getMessage(), e);
        }
    }

    // Called once the user has completed the real bank-login flow on the
    // frontend. Retrieves the now-linked accounts directly from Stripe and
    // creates each as an ALREADY-VERIFIED BankAccount row - logging into the
    // real bank through Stripe's flow is itself the verification, so there
    // is no separate self-click "Verify" step for accounts created this way.
    @Transactional
    public List<BankAccount> finalizeBankConnection(Long walletId, String sessionId, String accountHolderName) {
        try {
            Wallet wallet = walletRepository.findById(walletId)
                    .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));
            Session session = stripeClient.financialConnections().sessions().retrieve(sessionId);
            List<BankAccount> linked = new ArrayList<>();
            List<BankAccount> existing = bankAccountRepository.findByWalletId(walletId);
            for (com.stripe.model.financialconnections.Account account : session.getAccounts().getData()) {
                boolean alreadyLinked = existing.stream()
                        .anyMatch(a -> a.getProviderReference().equals(account.getId()));
                if (alreadyLinked) {
                    continue;
                }
                BankAccount bankAccount = new BankAccount(
                        walletId,
                        accountHolderName,
                        account.getInstitutionName(),
                        account.getLast4(),
                        account.getId()
                );
                bankAccount.markVerified();

                // Turn the verified Financial Connections account into a
                // real, chargeable Stripe PaymentMethod and attach it to
                // this user's Customer - this is what a future deposit
                // will actually charge. Not the bank account itself, a
                // Stripe object built from it with the user's consent.
                PaymentMethodCreateParams pmParams = PaymentMethodCreateParams.builder()
                        .setType(PaymentMethodCreateParams.Type.US_BANK_ACCOUNT)
                        .setBillingDetails(
                                PaymentMethodCreateParams.BillingDetails.builder()
                                        .setName(accountHolderName)
                                        .build()
                        )
                        .setUsBankAccount(
                                PaymentMethodCreateParams.UsBankAccount.builder()
                                        .setFinancialConnectionsAccount(account.getId())
                                        .build()
                        )
                        .build();
                PaymentMethod paymentMethod = stripeClient.paymentMethods().create(pmParams);
                stripeClient.paymentMethods().attach(
                        paymentMethod.getId(),
                        PaymentMethodAttachParams.builder().setCustomer(wallet.getStripeCustomerId()).build()
                );
                bankAccount.linkStripePaymentMethod(paymentMethod.getId());

                linked.add(bankAccountRepository.save(bankAccount));
            }
            return linked;
        } catch (StripeException e) {
            throw new IllegalStateException("Could not finalize bank connection: " + e.getMessage(), e);
        }
    }
}
