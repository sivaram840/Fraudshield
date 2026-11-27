package com.fraudshield.repo;

import com.fraudshield.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable page);

    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtAsc(String entityType, Long entityId);

    long countByAction(String action);
}
