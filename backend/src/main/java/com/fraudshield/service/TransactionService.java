package com.fraudshield.service;

import com.fraudshield.config.FraudShieldProperties;
import com.fraudshield.domain.*;
import com.fraudshield.repo.AccountRepository;
import com.fraudshield.repo.RiskAssessmentRepository;
import com.fraudshield.repo.TransactionRepository;
import com.fraudshield.risk.RiskEngine;
import com.fraudshield.risk.RuleHit;
import com.fraudshield.web.ApiException;
import com.fraudshield.web.dto.Dtos.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Transfer workflow: duplicate check -> validation -> risk assessment ->
 * decision (ALLOW / MFA / HOLD / BLOCK) -> execution only when cleared.
 */
@Service
public class TransactionService {
    private final TransactionRepository txns;
    private final AccountRepository accounts;
    private final RiskAssessmentRepository assessments;
    private final RiskEngine riskEngine;
    private final ExecutionService execution;
    private final OtpService otp;
    private final AlertService alerts;
    private final AuditService audit;
    private final FraudShieldProperties props;
    private final TransactionTemplate tx;

    public TransactionService(TransactionRepository txns, AccountRepository accounts,
                              RiskAssessmentRepository assessments, RiskEngine riskEngine,
                              ExecutionService execution, OtpService otp, AlertService alerts, AuditService audit,
                              FraudShieldProperties props, PlatformTransactionManager tm) {
        this.txns = txns;
        this.accounts = accounts;
        this.assessments = assessments;
        this.riskEngine = riskEngine;
        this.execution = execution;
        this.otp = otp;
        this.alerts = alerts;
        this.audit = audit;
        this.props = props;
        this.tx = new TransactionTemplate(tm);
    }

    public TransferResponse create(TransferRequest req, String headerKey, Origin origin) {
        String key = headerKey != null && !headerKey.isBlank() ? headerKey : req.idempotencyKey();
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED",
                    "Send an Idempotency-Key header (or idempotencyKey field) so retries cannot pay twice");
        }
        var existing = txns.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return duplicate(existing.get(), req);
        }

        Account from = accounts.findByAccountNumber(req.fromAccount())
                .orElseThrow(() -> ApiException.notFound("Account " + req.fromAccount()));
        Account to = accounts.findByAccountNumber(req.toAccount())
                .orElseThrow(() -> ApiException.notFound("Account " + req.toAccount()));
        BigDecimal amount = req.amount().setScale(2, RoundingMode.UNNECESSARY);
        if (from.getId().equals(to.getId())) {
            throw ApiException.badRequest("SAME_ACCOUNT", "Sender and receiver must be different accounts");
        }
        if (from.getStatus() == AccountStatus.FROZEN) {
            audit.log("TRANSFER_REFUSED", "ACCOUNT", from.getId(), AuditService.SYSTEM, "Sender frozen");
            throw ApiException.unprocessable("SENDER_FROZEN", "Sender account is frozen");
        }
        if (from.getBalance().compareTo(amount) < 0) {
            audit.log("TRANSFER_REFUSED", "ACCOUNT", from.getId(), AuditService.SYSTEM,
                    "Insufficient funds for Rs " + amount.toPlainString());
            throw ApiException.unprocessable("INSUFFICIENT_FUNDS", "Insufficient funds");
        }

        Instant now = Instant.now();
        RiskEngine.Assessment risk = riskEngine.assess(from, to, amount, now);

        Transaction saved;
        try {
            saved = tx.execute(s -> persist(req, key, origin, from, to, amount, now, risk));
        } catch (DataIntegrityViolationException e) {
            // A concurrent request with the same key won the race.
            return duplicate(txns.findByIdempotencyKey(key).orElseThrow(() -> e), req);
        }

        String demoOtp = null;
        String message;
        switch (risk.decision()) {
            case ALLOW -> {
                saved = execution.execute(saved.getId());
                message = saved.getStatus() == TxnStatus.COMPLETED ? "Transfer completed" : saved.getFailureReason();
            }
            case MFA -> {
                String code = otp.issue(saved);
                demoOtp = props.otp().demoMode() ? code : null;
                message = "Additional verification required: enter the OTP sent to the registered phone";
            }
            case HOLD -> message = "Transfer held for fraud-analyst review. It will not execute unless approved.";
            default -> message = "Transfer blocked: high risk of fraud or money-mule activity.";
        }
        return new TransferResponse(dto(saved), false, demoOtp, message);
    }

    private Transaction persist(TransferRequest req, String key, Origin origin, Account fromSnapshot,
                                Account toSnapshot, BigDecimal amount, Instant now, RiskEngine.Assessment risk) {
        // Re-read inside this transaction: the snapshots may be stale if other transfers ran meanwhile.
        Account from = accounts.findById(fromSnapshot.getId()).orElseThrow();
        Account to = accounts.findById(toSnapshot.getId()).orElseThrow();
        Transaction t = new Transaction();
        t.setReference("TXN" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        t.setIdempotencyKey(key);
        t.setFromAccount(from);
        t.setToAccount(to);
        t.setAmount(amount);
        t.setDescription(req.description());
        t.setOrigin(origin);
        t.setCreatedAt(now);
        t.setDecision(risk.decision());
        t.setRiskScore(bd(risk.finalScore()));
        t.setStatus(switch (risk.decision()) {
            case ALLOW -> TxnStatus.APPROVED;
            case MFA -> TxnStatus.PENDING_MFA;
            case HOLD -> TxnStatus.HELD;
            case BLOCK -> TxnStatus.BLOCKED;
        });
        if (risk.decision() == Decision.HOLD) {
            t.setHoldExpiresAt(now.plus(Duration.ofHours(props.hold().expiryHours())));
        }
        if (risk.decision() == Decision.BLOCK) {
            t.setFailureReason("Blocked by risk engine");
        }
        txns.saveAndFlush(t);

        RiskAssessment ra = new RiskAssessment();
        ra.setTransaction(t);
        risk.ml().ifPresent(m -> {
            ra.setTxnFraudProbability(bd(m.txnFraudProbability()));
            ra.setSenderMuleProbability(bd(m.senderMuleProbability()));
            ra.setReceiverMuleProbability(bd(m.receiverMuleProbability()));
            ra.setAnomalyScore(bd(m.anomalyScore()));
            ra.setModelVersion(m.modelVersion());
        });
        ra.setMlAvailable(risk.ml().isPresent());
        ra.setRuleScore(bd(risk.rules().score()));
        ra.setFinalScore(bd(risk.finalScore()));
        ra.setDecision(risk.decision());
        ra.setRulesFired(risk.rules().hits().stream().map(RuleHit::code).collect(Collectors.joining(",")));
        String reasons = String.join(" | ", risk.reasons());
        ra.setReasons(reasons.length() > 4000 ? reasons.substring(0, 4000) : reasons);
        ra.setCreatedAt(now);
        assessments.save(ra);

        audit.txn("TRANSFER_REQUESTED", t.getId(), String.format("%s -> %s Rs %s (key %s, origin %s)",
                from.getAccountNumber(), to.getAccountNumber(), amount.toPlainString(), key, origin));
        audit.txn("RISK_DECISION", t.getId(), String.format("%s score=%.4f rules=%.2f ml=%s fired=[%s]",
                risk.decision(), risk.finalScore(), risk.rules().score(),
                risk.ml().isPresent() ? "yes" : "unavailable", ra.getRulesFired()));
        alerts.raiseForDecision(t, risk);

        // Protect the network: an account caught forwarding pooled money is put under review.
        if (risk.decision() == Decision.BLOCK && (risk.rules().fired("RAPID_ONWARD") || risk.rules().fired("SENDER_FAN_IN"))
                && from.getStatus() == AccountStatus.ACTIVE) {
            from.setStatus(AccountStatus.UNDER_REVIEW);
            audit.log("ACCOUNT_UNDER_REVIEW", "ACCOUNT", from.getId(), AuditService.SYSTEM,
                    "Automatically placed under review after blocked mule-pattern transfer " + t.getReference());
        }
        return t;
    }

    private TransferResponse duplicate(Transaction t, TransferRequest req) {
        boolean same = t.getFromAccount().getAccountNumber().equals(req.fromAccount())
                && t.getToAccount().getAccountNumber().equals(req.toAccount())
                && t.getAmount().compareTo(req.amount()) == 0;
        if (!same) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "This idempotency key was already used for a different transfer");
        }
        audit.txn("DUPLICATE_REQUEST", t.getId(), "Replay of key " + t.getIdempotencyKey() + " ignored; no new transfer");
        return new TransferResponse(dto(t), true, null,
                "Duplicate request: returning the original transaction, nothing was charged again");
    }

    public OtpResult verifyOtp(Long id, String code) {
        OtpService.VerifyOutcome o = otp.verify(id, code);
        Transaction t = o.verified() ? execution.execute(id) : o.txn();
        String msg = o.verified()
                ? (t.getStatus() == TxnStatus.COMPLETED ? "Verified and completed" : t.getFailureReason())
                : o.message();
        return new OtpResult(dto(txns.findById(id).orElseThrow()), o.verified(), o.attemptsRemaining(), msg);
    }

    public TransferResponse resendOtp(Long id) {
        String code = otp.resend(id);
        return new TransferResponse(get(id), false, props.otp().demoMode() ? code : null, "New OTP sent");
    }

    public TransactionDto review(Long id, ReviewRequest req, String analyst) {
        tx.executeWithoutResult(s -> {
            Transaction t = txns.lockById(id).orElseThrow(() -> ApiException.notFound("Transaction " + id));
            if (t.getStatus() != TxnStatus.HELD) {
                throw ApiException.conflict("NOT_HELD", "Only HELD transactions can be reviewed; this one is " + t.getStatus());
            }
            if (req.action() == ReviewAction.APPROVE) {
                t.setStatus(TxnStatus.APPROVED);
                audit.log("REVIEW_APPROVED", "TRANSACTION", id, analyst, req.note());
            } else {
                t.setStatus(TxnStatus.REJECTED);
                t.setFailureReason("Rejected by analyst" + (req.note() == null ? "" : ": " + req.note()));
                audit.log("REVIEW_REJECTED", "TRANSACTION", id, analyst, req.note());
            }
        });
        if (req.action() == ReviewAction.APPROVE) {
            execution.execute(id);
        }
        return get(id);
    }

    /** The customer withdraws a transfer that is still waiting for OTP or review; it never executes. */
    public void cancelByCustomer(Long id, String username) {
        tx.executeWithoutResult(s -> {
            Transaction t = txns.lockById(id).orElseThrow(() -> ApiException.notFound("Transaction " + id));
            if (t.getStatus() != TxnStatus.PENDING_MFA && t.getStatus() != TxnStatus.HELD) {
                throw ApiException.conflict("NOT_CANCELLABLE", "Only transfers waiting for OTP or review can be cancelled");
            }
            t.setStatus(TxnStatus.REJECTED);
            t.setFailureReason("Cancelled by customer");
            audit.log("CANCELLED_BY_CUSTOMER", "TRANSACTION", id, username, "Customer withdrew the transfer");
        });
    }

    /** Held transactions nobody reviewed in time expire and never execute. */
    @Transactional
    public int expireStaleHolds() {
        List<Transaction> stale = txns.findByStatusAndHoldExpiresAtBefore(TxnStatus.HELD, Instant.now());
        for (Transaction t : stale) {
            t.setStatus(TxnStatus.EXPIRED);
            t.setFailureReason("Hold expired without analyst approval; not executed");
            audit.txn("HOLD_EXPIRED", t.getId(), "Unresolved hold expired; funds never moved");
        }
        return stale.size();
    }

    @Transactional(readOnly = true)
    public TransactionDto get(Long id) {
        return dto(txns.findById(id).orElseThrow(() -> ApiException.notFound("Transaction " + id)));
    }

    @Transactional(readOnly = true)
    public PageDto<TransactionDto> list(TxnStatus status, String accountNumber, int page, int size) {
        var pr = PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Transaction> p;
        if (accountNumber != null && !accountNumber.isBlank()) {
            Account a = accounts.findByAccountNumber(accountNumber)
                    .orElseThrow(() -> ApiException.notFound("Account " + accountNumber));
            p = txns.forAccount(a.getId(), PageRequest.of(page, Math.min(size, 200)));
        } else if (status != null) {
            p = txns.findByStatus(status, pr);
        } else {
            p = txns.findAll(pr);
        }
        return new PageDto<>(p.getContent().stream().map(this::dto).toList(), page, size, p.getTotalElements());
    }

    TransactionDto dto(Transaction t) {
        return TransactionDto.of(t, assessments.findByTransactionId(t.getId()).orElse(null));
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }
}
