package com.fraudshield.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    /** PBKDF2 hash, never the password itself. */
    private String passwordHash;
    private String displayName;
    @Enumerated(EnumType.STRING)
    private Role role;
    /** The customer's own account; null for staff. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "account_id")
    private Account account;
    private boolean active = true;
    private Instant createdAt;
    private Instant lastLoginAt;
}
