package com.fraudshield.repo;

import com.fraudshield.domain.Alert;
import com.fraudshield.domain.AlertStatus;
import com.fraudshield.domain.Severity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {
    Page<Alert> findByStatus(AlertStatus status, Pageable page);

    List<Alert> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    List<Alert> findByTransactionId(Long transactionId);

    long countByStatus(AlertStatus status);

    long countByAccountIdAndStatusAndSeverityIn(Long accountId, AlertStatus status, Collection<Severity> severities);
}
