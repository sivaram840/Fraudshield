package com.fraudshield.repo;

import com.fraudshield.domain.RiskAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, Long> {
    Optional<RiskAssessment> findByTransactionId(Long transactionId);
}
