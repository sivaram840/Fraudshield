package com.fraudshield.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "alerts")
@Getter
@Setter
@NoArgsConstructor
public class Alert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;
    @ManyToOne
    @JoinColumn(name = "account_id")
    private Account account;
    @Enumerated(EnumType.STRING)
    private AlertType type;
    @Enumerated(EnumType.STRING)
    private Severity severity;
    private String message;
    @Enumerated(EnumType.STRING)
    private AlertStatus status = AlertStatus.OPEN;
    private Instant createdAt;
    private Instant resolvedAt;
    private String resolutionNote;
}
