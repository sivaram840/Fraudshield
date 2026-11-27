package com.fraudshield.service;

import com.fraudshield.domain.*;
import com.fraudshield.repo.AccountRepository;
import com.fraudshield.repo.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Writes back-dated, already-completed history (marked origin SEED) so demo
 * accounts look like real customers with a past. It bypasses the risk engine on
 * purpose and is used only by the demo seeder and the attack scenarios.
 */
@Service
public class SeedService {
    private final TransactionRepository txns;
    private final AccountRepository accounts;

    public SeedService(TransactionRepository txns, AccountRepository accounts) {
        this.txns = txns;
        this.accounts = accounts;
    }

    @Transactional
    public void history(Account from, Account to, BigDecimal amount, Instant at) {
        Account f = accounts.lockById(from.getId()).orElseThrow();
        Account t = accounts.lockById(to.getId()).orElseThrow();
        if (f.getBalance().compareTo(amount) < 0) {
            return;
        }
        f.setBalance(f.getBalance().subtract(amount));
        t.setBalance(t.getBalance().add(amount));
        Transaction x = new Transaction();
        x.setReference("SEED" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        x.setIdempotencyKey("seed-" + UUID.randomUUID());
        x.setFromAccount(f);
        x.setToAccount(t);
        x.setAmount(amount);
        x.setDescription("Historical activity (seed)");
        x.setStatus(TxnStatus.COMPLETED);
        x.setDecision(Decision.ALLOW);
        x.setRiskScore(BigDecimal.ZERO.setScale(4));
        x.setOrigin(Origin.SEED);
        x.setCreatedAt(at);
        x.setExecutedAt(at);
        txns.save(x);
    }
}
