package com.fraudshield.service;

import com.fraudshield.domain.*;
import com.fraudshield.repo.*;
import com.fraudshield.web.ApiException;
import com.fraudshield.web.dto.Dtos.*;
import com.fraudshield.web.dto.PortalDtos.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Investigation cases: escalated by analysts or created from customer self-reports, worked by investigators. */
@Service
public class CaseService {
    static final int TRACE_HOURS = 14 * 24;
    private final InvestigationCaseRepository cases;
    private final AccountRepository accounts;
    private final AlertRepository alerts;
    private final TransactionRepository txns;
    private final RiskAssessmentRepository assessments;
    private final AuditLogRepository auditLogs;
    private final GraphService graph;
    private final AuditService audit;

    public CaseService(InvestigationCaseRepository cases, AccountRepository accounts, AlertRepository alerts,
                       TransactionRepository txns, RiskAssessmentRepository assessments, AuditLogRepository auditLogs,
                       GraphService graph, AuditService audit) {
        this.cases = cases;
        this.accounts = accounts;
        this.alerts = alerts;
        this.txns = txns;
        this.assessments = assessments;
        this.auditLogs = auditLogs;
        this.graph = graph;
        this.audit = audit;
    }

    @Transactional
    public InvestigationCase open(Account account, String title, String summary, String source, Severity priority,
                                  String actor) {
        InvestigationCase c = new InvestigationCase();
        c.setReference("CASE-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        c.setAccount(account);
        c.setTitle(title);
        c.setSummary(summary);
        c.setSource(source);
        c.setPriority(priority);
        c.setCreatedBy(actor);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        cases.save(c);
        audit.log("CASE_OPENED", "CASE", c.getId(), actor, c.getReference() + " on " + account.getAccountNumber()
                + " (" + source + "): " + title);
        return c;
    }

    @Transactional
    public CaseDto escalate(EscalateRequest req, String actor) {
        Account a = accounts.findByAccountNumber(req.accountNumber())
                .orElseThrow(() -> ApiException.notFound("Account " + req.accountNumber()));
        return CaseDto.of(open(a, req.title(), req.summary(), "ANALYST_ESCALATION", req.priority(), actor));
    }

    @Transactional(readOnly = true)
    public List<CaseDto> list() {
        return cases.findAllByOrderByCreatedAtDesc().stream().map(CaseDto::of).toList();
    }

    @Transactional
    public CaseDto update(Long id, UpdateCaseRequest req, String actor) {
        InvestigationCase c = cases.findById(id).orElseThrow(() -> ApiException.notFound("Case " + id));
        if (req.status() != null) {
            c.setStatus(req.status());
        }
        if (req.assignedTo() != null) {
            c.setAssignedTo(req.assignedTo().isBlank() ? null : req.assignedTo());
        }
        if (req.findings() != null) {
            c.setFindings(req.findings());
        }
        c.setUpdatedAt(Instant.now());
        audit.log("CASE_UPDATED", "CASE", id, actor, "status=" + c.getStatus() + " assigned=" + c.getAssignedTo());
        return CaseDto.of(c);
    }

    /** Not read-only: opening a case file is itself recorded in the audit log (chain of custody). */
    @Transactional
    public CaseDetail detail(Long id, String viewer) {
        InvestigationCase c = cases.findById(id).orElseThrow(() -> ApiException.notFound("Case " + id));
        Account a = c.getAccount();
        int traceHours = traceHours(c);
        GraphService.Trace trace = graph.trace(a, traceHours);

        Set<Long> involved = new LinkedHashSet<>();
        involved.add(a.getId());
        trace.involved().forEach(n -> accounts.findByAccountNumber(n.id()).ifPresent(x -> involved.add(x.getId())));
        List<AlertDto> relatedAlerts = involved.stream()
                .flatMap(accId -> alerts.findByAccountIdOrderByCreatedAtDesc(accId).stream())
                .map(AlertDto::of).toList();
        List<TransactionDto> history = txns.forAccount(a.getId(), PageRequest.of(0, 100)).getContent().stream()
                .map(t -> TransactionDto.of(t, assessments.findByTransactionId(t.getId()).orElse(null))).toList();
        List<AuditDto> accountAudit = auditLogs.findByEntityTypeAndEntityIdOrderByCreatedAtAsc("ACCOUNT", a.getId())
                .stream().map(AuditDto::of).toList();
        audit.log("CASE_VIEWED", "CASE", id, viewer, "Case file opened");
        return new CaseDetail(CaseDto.of(c), AccountDto.of(a), trace, relatedAlerts, history, accountAudit, traceHours,
                Instant.now());
    }

    /** Evidence window: 14 days before the case was opened, up to now, so older cases keep their victims and paths. */
    static int traceHours(InvestigationCase c) {
        long sinceOpened = c.getCreatedAt() == null ? 0 : Duration.between(c.getCreatedAt(), Instant.now()).toHours();
        return (int) Math.min(TRACE_HOURS + Math.max(0, sinceOpened), GraphService.MAX_HOURS);
    }
}
