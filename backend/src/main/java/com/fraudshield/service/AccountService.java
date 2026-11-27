package com.fraudshield.service;

import com.fraudshield.domain.Account;
import com.fraudshield.domain.AccountStatus;
import com.fraudshield.repo.AccountRepository;
import com.fraudshield.web.ApiException;
import com.fraudshield.web.dto.Dtos.CreateAccountRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

@Service
public class AccountService {
    private final SecureRandom random = new SecureRandom();
    private final AccountRepository repo;
    private final AuditService audit;

    public AccountService(AccountRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @Transactional
    public Account create(CreateAccountRequest req) {
        return create(req.holderName(), req.state(), req.phone(), req.initialBalance(), Instant.now());
    }

    @Transactional
    public Account create(String holder, String state, String phone, BigDecimal balance, Instant createdAt) {
        Account a = new Account();
        String number;
        do {
            number = "FS" + String.format("%010d", (long) (random.nextDouble() * 1e10));
        } while (repo.existsByAccountNumber(number));
        a.setAccountNumber(number);
        a.setHolderName(holder.trim());
        a.setState(state.trim());
        a.setPhone(phone);
        a.setBalance(balance.setScale(2, RoundingMode.HALF_UP));
        a.setStatus(AccountStatus.ACTIVE);
        a.setCreatedAt(createdAt);
        repo.save(a);
        audit.log("ACCOUNT_CREATED", "ACCOUNT", a.getId(), AuditService.SYSTEM,
                number + " for " + holder + " (" + state + "), opening balance Rs " + a.getBalance().toPlainString());
        return a;
    }

    @Transactional
    public Account updateStatus(String accountNumber, AccountStatus status, String note, String actor) {
        Account a = get(accountNumber);
        AccountStatus old = a.getStatus();
        a.setStatus(status);
        audit.log("ACCOUNT_STATUS_CHANGED", "ACCOUNT", a.getId(), actor, old + " -> " + status
                + (note == null ? "" : " (" + note + ")"));
        return a;
    }

    @Transactional(readOnly = true)
    public Account get(String accountNumber) {
        return repo.findByAccountNumber(accountNumber)
                .orElseThrow(() -> ApiException.notFound("Account " + accountNumber));
    }

    @Transactional(readOnly = true)
    public List<Account> list() {
        return repo.findAll(Sort.by("holderName"));
    }
}
