package com.fraudshield.service;

import com.fraudshield.domain.*;
import com.fraudshield.repo.AlertRepository;
import com.fraudshield.risk.RiskEngine;
import com.fraudshield.risk.RuleHit;
import com.fraudshield.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class AlertService {
    static final String MULE_WARNING_TEXT =
            "Your account received money from several unrelated people and is being used to pass it on quickly. "
                    + "If someone asked you to receive money and send it onward for a commission, stop: this is a "
                    + "money-mule scam and the money is likely stolen. Do not forward it; call the cybercrime "
                    + "helpline 1930 or report at cybercrime.gov.in.";

    private final AlertRepository repo;
    private final AuditService audit;

    public AlertService(AlertRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public Alert raise(AlertType type, Severity severity, String message, Account account, Transaction txn) {
        Alert a = new Alert();
        a.setType(type);
        a.setSeverity(severity);
        a.setMessage(message);
        a.setAccount(account);
        a.setTransaction(txn);
        a.setCreatedAt(Instant.now());
        repo.save(a);
        audit.log("ALERT_RAISED", "ALERT", a.getId(), AuditService.SYSTEM, type + " " + severity + ": " + message);
        return a;
    }

    /** Raises explained alerts for a risky decision. ALLOW decisions raise nothing. */
    public List<Alert> raiseForDecision(Transaction txn, RiskEngine.Assessment risk) {
        List<Alert> out = new ArrayList<>();
        if (risk.decision() == Decision.ALLOW) {
            return out;
        }
        Severity sev = switch (risk.decision()) {
            case BLOCK -> Severity.CRITICAL;
            case HOLD -> Severity.HIGH;
            default -> Severity.MEDIUM;
        };
        boolean senderMule = false;
        boolean receiverMule = false;
        for (RuleHit h : risk.rules().hits()) {
            Account subject = "RECEIVER".equals(h.about()) ? txn.getToAccount() : txn.getFromAccount();
            AlertType type = switch (h.code()) {
                case "RECEIVER_FAN_IN", "SENDER_FAN_IN" -> AlertType.FAN_IN;
                case "RAPID_ONWARD" -> AlertType.RAPID_ONWARD_TRANSFER;
                case "MULTI_STAGE_CHAIN" -> AlertType.MULTI_STAGE_CHAIN;
                case "RECEIVER_FROZEN" -> AlertType.FROZEN_COUNTERPARTY;
                default -> null;
            };
            if (type != null) {
                out.add(raise(type, sev, h.message() + " [" + txn.getReference() + "]", subject, txn));
            }
            senderMule |= h.code().equals("RAPID_ONWARD") || h.code().equals("SENDER_FAN_IN");
            receiverMule |= h.code().equals("RECEIVER_FAN_IN");
        }
        if (risk.decision() == Decision.HOLD || risk.decision() == Decision.BLOCK) {
            String why = String.join("; ", risk.reasons().subList(0, Math.min(4, risk.reasons().size())));
            out.add(raise(AlertType.HIGH_RISK_TRANSACTION, sev,
                    String.format("%s %s of Rs %s (score %.2f): %s", txn.getReference(), risk.decision(),
                            txn.getAmount().toPlainString(), risk.finalScore(), why),
                    txn.getFromAccount(), txn));
        }
        // Warn the likely intermediary directly: it is often an unwitting victim of the scam too.
        if (senderMule) {
            out.add(raise(AlertType.MULE_WARNING, Severity.HIGH, MULE_WARNING_TEXT, txn.getFromAccount(), txn));
        } else if (receiverMule && risk.decision() != Decision.MFA) {
            out.add(raise(AlertType.MULE_WARNING, Severity.HIGH, MULE_WARNING_TEXT, txn.getToAccount(), txn));
        }
        return out;
    }

    @Transactional
    public Alert resolve(Long id, String note) {
        Alert a = repo.findById(id).orElseThrow(() -> ApiException.notFound("Alert " + id));
        if (a.getStatus() == AlertStatus.RESOLVED) {
            throw ApiException.conflict("ALERT_ALREADY_RESOLVED", "Alert " + id + " is already resolved");
        }
        a.setStatus(AlertStatus.RESOLVED);
        a.setResolvedAt(Instant.now());
        a.setResolutionNote(note);
        audit.log("ALERT_RESOLVED", "ALERT", id, "analyst", note);
        return a;
    }
}
