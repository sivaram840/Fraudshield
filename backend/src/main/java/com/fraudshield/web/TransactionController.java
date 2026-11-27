package com.fraudshield.web;

import com.fraudshield.domain.Role;
import com.fraudshield.security.AuthInterceptor;
import com.fraudshield.security.RequireRole;
import com.fraudshield.domain.Origin;
import jakarta.servlet.http.HttpServletRequest;
import com.fraudshield.domain.TxnStatus;
import com.fraudshield.repo.AuditLogRepository;
import com.fraudshield.service.TransactionService;
import com.fraudshield.web.dto.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
@RequireRole({Role.ANALYST, Role.INVESTIGATOR})
public class TransactionController {
    private final TransactionService transactions;
    private final AuditLogRepository audit;

    public TransactionController(TransactionService transactions, AuditLogRepository audit) {
        this.transactions = transactions;
        this.audit = audit;
    }

    /** Bank-operations transfer between any two accounts (customers use /api/me/transfers). 201 for a new transfer, 200 with header Idempotent-Replay: true for a duplicate. */
    @PostMapping
    @RequireRole(Role.ADMIN)
    public ResponseEntity<TransferResponse> create(@Valid @RequestBody TransferRequest req,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        TransferResponse r = transactions.create(req, key, Origin.API);
        return ResponseEntity.status(r.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replay", String.valueOf(r.duplicate()))
                .body(r);
    }

    @GetMapping
    public PageDto<TransactionDto> list(@RequestParam(required = false) TxnStatus status,
                                        @RequestParam(required = false) String account,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return transactions.list(status, account, page, size);
    }

    @GetMapping("/{id}")
    public TransactionDto get(@PathVariable Long id) {
        return transactions.get(id);
    }

    @GetMapping("/{id}/audit")
    public List<AuditDto> audit(@PathVariable Long id) {
        return audit.findByEntityTypeAndEntityIdOrderByCreatedAtAsc("TRANSACTION", id).stream().map(AuditDto::of).toList();
    }

    @PostMapping("/{id}/otp/verify")
    @RequireRole(Role.ADMIN)
    public OtpResult verify(@PathVariable Long id, @Valid @RequestBody OtpVerifyRequest req) {
        return transactions.verifyOtp(id, req.code());
    }

    @PostMapping("/{id}/otp/resend")
    @RequireRole(Role.ADMIN)
    public TransferResponse resend(@PathVariable Long id) {
        return transactions.resendOtp(id);
    }

    @PostMapping("/{id}/review")
    @RequireRole(Role.ANALYST)
    public TransactionDto review(@PathVariable Long id, @Valid @RequestBody ReviewRequest req, HttpServletRequest http) {
        return transactions.review(id, req, AuthInterceptor.current(http).getUsername());
    }
}
