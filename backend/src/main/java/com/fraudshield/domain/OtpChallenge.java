package com.fraudshield.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "otp_challenges")
@Getter
@Setter
@NoArgsConstructor
public class OtpChallenge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;
    /** SHA-256 of the code; the plain code is never stored. */
    private String codeHash;
    private Instant expiresAt;
    private int attempts;
    @Enumerated(EnumType.STRING)
    private OtpStatus status = OtpStatus.PENDING;
    private Instant createdAt;
}
