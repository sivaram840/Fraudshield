package com.fraudshield.web.dto;

import com.fraudshield.domain.*;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Request and response bodies of the REST API. */
public final class Dtos {
    private Dtos() {
    }

    // ---- requests ------------------------------------------------------------

    public record CreateAccountRequest(
            @NotBlank @Size(max = 100) String holderName,
            @NotBlank @Size(max = 50) String state,
            @Size(max = 20) String phone,
            @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal initialBalance) {
    }

    public record UpdateAccountStatusRequest(@NotNull AccountStatus status, @Size(max = 300) String note) {
    }

    public record TransferRequest(
            @NotBlank String fromAccount,
            @NotBlank String toAccount,
            @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @Size(max = 255) String description,
            @Size(max = 80) String idempotencyKey) {
    }

    public record OtpVerifyRequest(@NotBlank @Pattern(regexp = "\\d{6}") String code) {
    }

    public record ReviewRequest(@NotNull ReviewAction action, @Size(max = 500) String note) {
    }

    public enum ReviewAction { APPROVE, REJECT }

    public record ResolveAlertRequest(@Size(max = 500) String note) {
    }

    // ---- responses -----------------------------------------------------------

    public record AccountDto(Long id, String accountNumber, String holderName, String state, String phone,
                             BigDecimal balance, AccountStatus status, Instant createdAt) {
        public static AccountDto of(Account a) {
            return new AccountDto(a.getId(), a.getAccountNumber(), a.getHolderName(), a.getState(), a.getPhone(),
                    a.getBalance(), a.getStatus(), a.getCreatedAt());
        }
    }

    public record RiskDto(BigDecimal txnFraudProbability, BigDecimal senderMuleProbability,
                          BigDecimal receiverMuleProbability, BigDecimal anomalyScore, BigDecimal ruleScore,
                          BigDecimal finalScore, Decision decision, boolean mlAvailable, String modelVersion,
                          List<String> rulesFired, List<String> reasons, Instant assessedAt) {
        public static RiskDto of(RiskAssessment r) {
            if (r == null) {
                return null;
            }
            return new RiskDto(r.getTxnFraudProbability(), r.getSenderMuleProbability(),
                    r.getReceiverMuleProbability(), r.getAnomalyScore(), r.getRuleScore(), r.getFinalScore(),
                    r.getDecision(), r.isMlAvailable(), r.getModelVersion(), split(r.getRulesFired(), ","),
                    split(r.getReasons(), " \\| "), r.getCreatedAt());
        }

        private static List<String> split(String s, String sep) {
            return s == null || s.isBlank() ? List.of() : Arrays.asList(s.split(sep));
        }
    }

    public record TransactionDto(Long id, String reference, String fromAccount, String fromHolder, String toAccount,
                                 String toHolder, BigDecimal amount, String description, TxnStatus status,
                                 Decision decision, BigDecimal riskScore, String failureReason, Origin origin,
                                 Instant createdAt, Instant executedAt, Instant holdExpiresAt, RiskDto risk) {
        public static TransactionDto of(Transaction t, RiskAssessment r) {
            return new TransactionDto(t.getId(), t.getReference(), t.getFromAccount().getAccountNumber(),
                    t.getFromAccount().getHolderName(), t.getToAccount().getAccountNumber(),
                    t.getToAccount().getHolderName(), t.getAmount(), t.getDescription(), t.getStatus(),
                    t.getDecision(), t.getRiskScore(), t.getFailureReason(), t.getOrigin(), t.getCreatedAt(),
                    t.getExecutedAt(), t.getHoldExpiresAt(), RiskDto.of(r));
        }
    }

    /**
     * @param demoOtp only filled in demo mode, where it stands in for the SMS a real bank would send
     */
    public record TransferResponse(TransactionDto transaction, boolean duplicate, String demoOtp, String message) {
    }

    public record OtpResult(TransactionDto transaction, boolean verified, int attemptsRemaining, String message) {
    }

    public record AlertDto(Long id, AlertType type, Severity severity, String message, AlertStatus status,
                           String accountNumber, String accountHolder, Long transactionId,
                           String transactionReference, Instant createdAt, Instant resolvedAt,
                           String resolutionNote) {
        public static AlertDto of(Alert a) {
            return new AlertDto(a.getId(), a.getType(), a.getSeverity(), a.getMessage(), a.getStatus(),
                    a.getAccount() == null ? null : a.getAccount().getAccountNumber(),
                    a.getAccount() == null ? null : a.getAccount().getHolderName(),
                    a.getTransaction() == null ? null : a.getTransaction().getId(),
                    a.getTransaction() == null ? null : a.getTransaction().getReference(),
                    a.getCreatedAt(), a.getResolvedAt(), a.getResolutionNote());
        }
    }

    public record AuditDto(Long id, String action, String entityType, Long entityId, String actor, String details,
                           Instant createdAt) {
        public static AuditDto of(AuditLog l) {
            return new AuditDto(l.getId(), l.getAction(), l.getEntityType(), l.getEntityId(), l.getActor(),
                    l.getDetails(), l.getCreatedAt());
        }
    }

    public record PageDto<T>(List<T> items, int page, int size, long total) {
    }
}
