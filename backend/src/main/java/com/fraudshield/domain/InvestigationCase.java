package com.fraudshield.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A case handed from the bank (analyst or customer self-report) to investigators. */
@Entity
@Table(name = "investigation_cases")
@Getter
@Setter
@NoArgsConstructor
public class InvestigationCase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String reference;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "account_id")
    private Account account;
    private String title;
    private String summary;
    /** ANALYST_ESCALATION or CUSTOMER_REPORT. */
    private String source;
    @Enumerated(EnumType.STRING)
    private Severity priority;
    @Enumerated(EnumType.STRING)
    private CaseStatus status = CaseStatus.OPEN;
    private String createdBy;
    private String assignedTo;
    private String findings;
    private Instant createdAt;
    private Instant updatedAt;
}
