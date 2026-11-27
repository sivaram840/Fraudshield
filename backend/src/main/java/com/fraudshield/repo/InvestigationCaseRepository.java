package com.fraudshield.repo;

import com.fraudshield.domain.CaseStatus;
import com.fraudshield.domain.InvestigationCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvestigationCaseRepository extends JpaRepository<InvestigationCase, Long> {
    List<InvestigationCase> findAllByOrderByCreatedAtDesc();

    List<InvestigationCase> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    long countByStatusNot(CaseStatus status);
}
