package com.fraudshield.repo;

import com.fraudshield.domain.OtpChallenge;
import com.fraudshield.domain.OtpStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, Long> {
    Optional<OtpChallenge> findFirstByTransactionIdAndStatusOrderByCreatedAtDesc(Long transactionId, OtpStatus status);
}
