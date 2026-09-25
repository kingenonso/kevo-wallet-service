package com.kevo.wallet.service;

import com.kevo.wallet.entity.BankAccount;
import com.kevo.wallet.entity.BankAccountStatus;
import com.kevo.wallet.repository.BankAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BankAccountService {

    private final BankAccountRepository bankAccountRepository;

    public BankAccountService(BankAccountRepository bankAccountRepository) {
        this.bankAccountRepository = bankAccountRepository;
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
}
