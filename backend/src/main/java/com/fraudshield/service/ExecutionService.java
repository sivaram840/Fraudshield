package com.fraudshield.service;

import com.fraudshield.domain.Account;
import com.fraudshield.domain.AccountStatus;
import com.fraudshield.domain.Transaction;
import com.fraudshield.domain.TxnStatus;
import com.fraudshield.repo.AccountRepository;
import com.fraudshield.repo.TransactionRepository;
import com.fraudshield.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The only place money moves. Runs in one database transaction, locks the
 * transaction row and both account rows (in id order, to avoid deadlocks) and
 * only executes transactions in APPROVED state, so blocked, held, rejected,
 * expired or already-completed transactions can never be executed or executed twice.
 */
@Service
public class ExecutionService {
    private final TransactionRepository txns;
    private final AccountRepository accounts;
    private final AuditService audit;

    public ExecutionService(TransactionRepository txns, AccountRepository accounts, AuditService audit) {
        this.txns = txns;
        this.accounts = accounts;
        this.audit = audit;
    }

    @Transactional
    public Transaction execute(Long txnId) {
        // Lock the accounts before loading the transaction, so the transaction's account
        // references resolve to the freshly locked rows instead of stale copies.
        Long a = txns.fromAccountId(txnId).orElseThrow(() -> ApiException.notFound("Transaction " + txnId));
        Long b = txns.toAccountId(txnId).orElseThrow();
        Account first = accounts.lockById(Math.min(a, b)).orElseThrow();
        Account second = accounts.lockById(Math.max(a, b)).orElseThrow();
        Account from = first.getId().equals(a) ? first : second;
        Account to = first.getId().equals(a) ? second : first;
        Transaction t = txns.lockById(txnId).orElseThrow();
        if (t.getStatus() != TxnStatus.APPROVED) {
            throw ApiException.conflict("NOT_EXECUTABLE",
                    "Transaction " + t.getReference() + " is " + t.getStatus() + " and cannot be executed");
        }

        String failure = null;
        if (from.getStatus() == AccountStatus.FROZEN) {
            failure = "Sender account is frozen";
        } else if (to.getStatus() == AccountStatus.FROZEN) {
            failure = "Receiver account is frozen";
        } else if (from.getBalance().compareTo(t.getAmount()) < 0) {
            failure = "Insufficient funds at execution time";
        }
        if (failure != null) {
            t.setStatus(TxnStatus.FAILED);
            t.setFailureReason(failure);
            audit.txn("EXECUTION_FAILED", t.getId(), failure);
            return t;
        }
        from.setBalance(from.getBalance().subtract(t.getAmount()));
        to.setBalance(to.getBalance().add(t.getAmount()));
        t.setStatus(TxnStatus.COMPLETED);
        t.setExecutedAt(Instant.now());
        audit.txn("EXECUTED", t.getId(), String.format("Moved Rs %s from %s to %s. Balances now %s / %s",
                t.getAmount().toPlainString(), from.getAccountNumber(), to.getAccountNumber(),
                from.getBalance().toPlainString(), to.getBalance().toPlainString()));
        return t;
    }
}
