package com.fraudshield.web.dto;

import com.fraudshield.domain.*;
import com.fraudshield.service.GraphService;
import com.fraudshield.web.dto.Dtos.*;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Bodies for login, the customer portal, investigation cases and administration. */
public final class PortalDtos {
    private PortalDtos() {
    }

    // ---- auth ------------------------------------------------------------------

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record UserDto(Long id, String username, String displayName, Role role, String accountNumber,
                          boolean active, Instant createdAt, Instant lastLoginAt) {
        public static UserDto of(AppUser u) {
            return new UserDto(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole(),
                    u.getAccount() == null ? null : u.getAccount().getAccountNumber(), u.isActive(), u.getCreatedAt(),
                    u.getLastLoginAt());
        }
    }

    public record LoginResponse(String token, UserDto user) {
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{3,50}") String username,
            @NotBlank @Size(min = 8, max = 100) String password,
            @NotBlank @Size(max = 100) String displayName,
            @NotNull Role role,
            String accountNumber) {
    }

    public record SetActiveRequest(boolean active) {
    }

    // ---- customer portal ---------------------------------------------------------

    public record CustomerTransferRequest(
            @NotBlank String toAccount,
            @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @Size(max = 255) String description) {
    }

    public record CustomerTxn(Long id, String reference, String direction, String counterpartyName,
                              String counterpartyAccount, BigDecimal amount, TxnStatus status, String statusText,
                              String description, Instant createdAt, boolean canVerifyOtp, boolean canCancel) {
    }

    public record CustomerTransferResponse(CustomerTxn transaction, boolean duplicate, String demoOtp, String message) {
    }

    public record CustomerOtpResult(CustomerTxn transaction, boolean verified, int attemptsRemaining, String message) {
    }

    public record Payee(String accountNumber, String maskedName, String state) {
    }

    public record CustomerOverview(AccountDto account, List<AlertDto> warnings, List<CustomerTxn> recent,
                                   long pendingActions) {
    }

    public enum ReportType { UNAUTHORISED_TRANSACTION, ASKED_TO_MOVE_MONEY, SUSPICIOUS_CREDIT, OTHER }

    public record CustomerReportRequest(@NotNull ReportType type, Long transactionId,
                                        @NotBlank @Size(max = 1000) String details) {
    }

    public record CustomerReportResponse(String caseReference, String message) {
    }

    // ---- investigation cases -------------------------------------------------------

    public record EscalateRequest(@NotBlank String accountNumber, @NotBlank @Size(max = 200) String title,
                                  @Size(max = 2000) String summary, @NotNull Severity priority) {
    }

    public record UpdateCaseRequest(CaseStatus status, @Size(max = 50) String assignedTo,
                                    @Size(max = 4000) String findings) {
    }

    public record CaseDto(Long id, String reference, String accountNumber, String accountHolder, String title,
                          String summary, String source, Severity priority, CaseStatus status, String createdBy,
                          String assignedTo, String findings, Instant createdAt, Instant updatedAt) {
        public static CaseDto of(InvestigationCase c) {
            return new CaseDto(c.getId(), c.getReference(), c.getAccount().getAccountNumber(),
                    c.getAccount().getHolderName(), c.getTitle(), c.getSummary(), c.getSource(), c.getPriority(),
                    c.getStatus(), c.getCreatedBy(), c.getAssignedTo(), c.getFindings(), c.getCreatedAt(),
                    c.getUpdatedAt());
        }
    }

    /** Everything an investigator needs in one response; also the basis of the printable report. */
    public record CaseDetail(CaseDto caseInfo, AccountDto account, GraphService.Trace trace, List<AlertDto> alerts,
                             List<TransactionDto> transactions, List<AuditDto> accountAudit, int traceHours,
                             Instant generatedAt) {
    }
}
