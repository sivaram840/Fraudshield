package com.fraudshield.service;

import com.fraudshield.config.FraudShieldProperties;
import com.fraudshield.domain.*;
import com.fraudshield.repo.OtpChallengeRepository;
import com.fraudshield.repo.TransactionRepository;
import com.fraudshield.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class OtpService {
    private final SecureRandom random = new SecureRandom();
    private final OtpChallengeRepository otps;
    private final TransactionRepository txns;
    private final AuditService audit;
    private final AlertService alerts;
    private final FraudShieldProperties props;

    public OtpService(OtpChallengeRepository otps, TransactionRepository txns, AuditService audit,
                      AlertService alerts, FraudShieldProperties props) {
        this.otps = otps;
        this.txns = txns;
        this.audit = audit;
        this.alerts = alerts;
        this.props = props;
    }

    public record VerifyOutcome(Transaction txn, boolean verified, int attemptsRemaining, String message) {
    }

    /** Creates a challenge and returns the plain code (to be "sent by SMS"). */
    @Transactional
    public String issue(Transaction txn) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        OtpChallenge c = new OtpChallenge();
        c.setTransaction(txn);
        c.setCodeHash(sha256(code));
        c.setCreatedAt(Instant.now());
        c.setExpiresAt(Instant.now().plusSeconds(props.otp().ttlSeconds()));
        otps.save(c);
        audit.txn("OTP_ISSUED", txn.getId(), "OTP sent to registered phone of " + txn.getFromAccount().getAccountNumber());
        return code;
    }

    /** Verifies a code. On success the transaction becomes APPROVED (the caller executes it). */
    @Transactional
    public VerifyOutcome verify(Long txnId, String code) {
        Transaction t = txns.lockById(txnId).orElseThrow(() -> ApiException.notFound("Transaction " + txnId));
        if (t.getStatus() != TxnStatus.PENDING_MFA) {
            throw ApiException.conflict("NOT_AWAITING_MFA", "Transaction is " + t.getStatus() + ", not awaiting MFA");
        }
        OtpChallenge c = otps.findFirstByTransactionIdAndStatusOrderByCreatedAtDesc(txnId, OtpStatus.PENDING)
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_OTP", "No active OTP; request a new one"));
        int max = props.otp().maxAttempts();
        if (Instant.now().isAfter(c.getExpiresAt())) {
            c.setStatus(OtpStatus.EXPIRED);
            audit.txn("OTP_EXPIRED", txnId, "OTP expired before use");
            return new VerifyOutcome(t, false, max - c.getAttempts(), "OTP expired. Request a new code.");
        }
        c.setAttempts(c.getAttempts() + 1);
        if (MessageDigest.isEqual(c.getCodeHash().getBytes(), sha256(code).getBytes())) {
            c.setStatus(OtpStatus.VERIFIED);
            t.setStatus(TxnStatus.APPROVED);
            audit.txn("OTP_VERIFIED", txnId, "MFA passed on attempt " + c.getAttempts());
            return new VerifyOutcome(t, true, max - c.getAttempts(), "Verified");
        }
        int left = max - c.getAttempts();
        if (left <= 0) {
            c.setStatus(OtpStatus.FAILED);
            t.setStatus(TxnStatus.REJECTED);
            t.setFailureReason("MFA failed: too many wrong OTP attempts");
            audit.txn("OTP_FAILED", txnId, "Wrong OTP " + max + " times; transaction rejected");
            alerts.raise(AlertType.MFA_FAILED, Severity.HIGH,
                    "Transaction " + t.getReference() + " rejected after " + max
                            + " wrong OTP attempts. Possible account takeover.", t.getFromAccount(), t);
            return new VerifyOutcome(t, false, 0, "Too many wrong attempts. Transaction rejected.");
        }
        audit.txn("OTP_WRONG", txnId, "Wrong OTP, " + left + " attempts left");
        return new VerifyOutcome(t, false, left, "Wrong code. " + left + " attempt(s) left.");
    }

    @Transactional
    public String resend(Long txnId) {
        Transaction t = txns.lockById(txnId).orElseThrow(() -> ApiException.notFound("Transaction " + txnId));
        if (t.getStatus() != TxnStatus.PENDING_MFA) {
            throw ApiException.conflict("NOT_AWAITING_MFA", "Transaction is " + t.getStatus() + ", not awaiting MFA");
        }
        otps.findFirstByTransactionIdAndStatusOrderByCreatedAtDesc(txnId, OtpStatus.PENDING)
                .ifPresent(old -> old.setStatus(OtpStatus.EXPIRED));
        return issue(t);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
