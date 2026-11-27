package com.fraudshield.service;

import com.fraudshield.domain.AuditLog;
import com.fraudshield.repo.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AuditService {
    public static final String SYSTEM = "system";
    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    public void log(String action, String entityType, Long entityId, String actor, String details) {
        AuditLog l = new AuditLog();
        l.setAction(action);
        l.setEntityType(entityType);
        l.setEntityId(entityId);
        l.setActor(actor);
        l.setDetails(details == null ? null : details.length() > 2000 ? details.substring(0, 2000) : details);
        l.setCreatedAt(Instant.now());
        repo.save(l);
    }

    public void txn(String action, Long txnId, String details) {
        log(action, "TRANSACTION", txnId, SYSTEM, details);
    }
}
