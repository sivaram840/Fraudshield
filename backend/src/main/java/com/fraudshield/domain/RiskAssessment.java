package com.fraudshield.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** The full record of why a transaction got its decision (risk-decision audit trail). */
@Entity
@Table(name = "risk_assessments")
@Getter
@Setter
@NoArgsConstructor
public class RiskAssessment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;
    @Column(name = "txn_fraud_probability")
    private BigDecimal txnFraudProbability;
    private BigDecimal senderMuleProbability;
    private BigDecimal receiverMuleProbability;
    private BigDecimal anomalyScore;
    private BigDecimal ruleScore;
    private BigDecimal finalScore;
    @Enumerated(EnumType.STRING)
    private Decision decision;
    private boolean mlAvailable;
    private String modelVersion;
    /** Comma-separated rule codes. */
    private String rulesFired;
    /** Human-readable reasons joined with " | ". */
    private String reasons;
    private Instant createdAt;
}
