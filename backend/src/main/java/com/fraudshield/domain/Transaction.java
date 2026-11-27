package com.fraudshield.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String reference;
    private String idempotencyKey;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "from_account_id")
    private Account fromAccount;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "to_account_id")
    private Account toAccount;
    private BigDecimal amount;
    private String description;
    @Enumerated(EnumType.STRING)
    private TxnStatus status;
    @Enumerated(EnumType.STRING)
    private Decision decision;
    private BigDecimal riskScore;
    private String failureReason;
    @Enumerated(EnumType.STRING)
    private Origin origin = Origin.API;
    private Instant createdAt;
    private Instant executedAt;
    private Instant holdExpiresAt;
    @Version
    private long version;
}
