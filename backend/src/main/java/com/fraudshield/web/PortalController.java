package com.fraudshield.web;

import com.fraudshield.config.FraudShieldProperties;
import com.fraudshield.domain.Account;
import com.fraudshield.domain.AppUser;
import com.fraudshield.domain.Role;
import com.fraudshield.repo.AccountRepository;
import com.fraudshield.repo.AppUserRepository;
import com.fraudshield.security.AuthInterceptor;
import com.fraudshield.security.AuthService;
import com.fraudshield.security.RequireRole;
import com.fraudshield.service.AuditService;
import com.fraudshield.service.CaseService;
import com.fraudshield.service.CustomerService;
import com.fraudshield.web.dto.Dtos.OtpVerifyRequest;
import com.fraudshield.web.dto.PortalDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Login, the customer portal (/api/me), investigation cases and administration. */
@RestController
@RequestMapping("/api")
public class PortalController {
    private final AuthService auth;
    private final CustomerService customers;
    private final CaseService cases;
    private final AppUserRepository users;
    private final AccountRepository accounts;
    private final AuditService audit;
    private final FraudShieldProperties props;

    public PortalController(AuthService auth, CustomerService customers, CaseService cases, AppUserRepository users,
                            AccountRepository accounts, AuditService audit, FraudShieldProperties props) {
        this.auth = auth;
        this.customers = customers;
        this.cases = cases;
        this.users = users;
        this.accounts = accounts;
        this.audit = audit;
        this.props = props;
    }

    private static AppUser me(HttpServletRequest http) {
        return AuthInterceptor.current(http);
    }

    // ---- auth ------------------------------------------------------------------

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        AuthService.Login l = auth.login(req.username(), req.password());
        return new LoginResponse(l.token(), UserDto.of(l.user()));
    }

    @GetMapping("/auth/me")
    public UserDto whoAmI(HttpServletRequest http) {
        return UserDto.of(me(http));
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader("Authorization") String header) {
        auth.logout(header.substring(7).trim());
    }

    // ---- customer portal -----------------------------------------------------------

    @GetMapping("/me/overview")
    @RequireRole(Role.CUSTOMER)
    public CustomerOverview overview(HttpServletRequest http) {
        return customers.overview(me(http));
    }

    @GetMapping("/me/transactions")
    @RequireRole(Role.CUSTOMER)
    public List<CustomerTxn> myTransactions(HttpServletRequest http) {
        return customers.history(me(http), 200);
    }

    @GetMapping("/me/payees/{accountNumber}")
    @RequireRole(Role.CUSTOMER)
    public Payee payee(@PathVariable String accountNumber) {
        return customers.payee(accountNumber);
    }

    @PostMapping("/me/transfers")
    @RequireRole(Role.CUSTOMER)
    public ResponseEntity<CustomerTransferResponse> transfer(@Valid @RequestBody CustomerTransferRequest req,
                                                             @RequestHeader("Idempotency-Key") String key,
                                                             HttpServletRequest http) {
        CustomerTransferResponse r = customers.transfer(me(http), req, key);
        return ResponseEntity.status(r.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replay", String.valueOf(r.duplicate())).body(r);
    }

    @PostMapping("/me/transfers/{id}/otp/verify")
    @RequireRole(Role.CUSTOMER)
    public CustomerOtpResult verify(@PathVariable Long id, @Valid @RequestBody OtpVerifyRequest req,
                                    HttpServletRequest http) {
        return customers.verifyOtp(me(http), id, req.code());
    }

    @PostMapping("/me/transfers/{id}/otp/resend")
    @RequireRole(Role.CUSTOMER)
    public CustomerTransferResponse resend(@PathVariable Long id, HttpServletRequest http) {
        return customers.resendOtp(me(http), id);
    }

    @PostMapping("/me/transfers/{id}/cancel")
    @RequireRole(Role.CUSTOMER)
    public CustomerTxn cancel(@PathVariable Long id, HttpServletRequest http) {
        return customers.cancel(me(http), id);
    }

    @PostMapping("/me/reports")
    @RequireRole(Role.CUSTOMER)
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerReportResponse report(@Valid @RequestBody CustomerReportRequest req, HttpServletRequest http) {
        return customers.report(me(http), req);
    }

    // ---- investigation cases -------------------------------------------------------------

    @GetMapping("/cases")
    @RequireRole({Role.ANALYST, Role.INVESTIGATOR})
    public List<CaseDto> cases() {
        return cases.list();
    }

    @PostMapping("/cases")
    @RequireRole(Role.ANALYST)
    @ResponseStatus(HttpStatus.CREATED)
    public CaseDto escalate(@Valid @RequestBody EscalateRequest req, HttpServletRequest http) {
        return cases.escalate(req, me(http).getUsername());
    }

    @GetMapping("/cases/{id}")
    @RequireRole(Role.INVESTIGATOR)
    public CaseDetail caseDetail(@PathVariable Long id, HttpServletRequest http) {
        return cases.detail(id, me(http).getUsername());
    }

    @PatchMapping("/cases/{id}")
    @RequireRole(Role.INVESTIGATOR)
    public CaseDto updateCase(@PathVariable Long id, @Valid @RequestBody UpdateCaseRequest req, HttpServletRequest http) {
        return cases.update(id, req, me(http).getUsername());
    }

    // ---- administration --------------------------------------------------------------------

    @GetMapping("/admin/users")
    @RequireRole(Role.ADMIN)
    public List<UserDto> listUsers() {
        return users.findAllByOrderByRoleAscUsernameAsc().stream().map(UserDto::of).toList();
    }

    @PostMapping("/admin/users")
    @RequireRole(Role.ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto createUser(@Valid @RequestBody CreateUserRequest req, HttpServletRequest http) {
        Account account = null;
        if (req.accountNumber() != null && !req.accountNumber().isBlank()) {
            account = accounts.findByAccountNumber(req.accountNumber().trim())
                    .orElseThrow(() -> ApiException.notFound("Account " + req.accountNumber()));
        }
        return UserDto.of(auth.createUser(req.username(), req.password(), req.displayName(), req.role(), account,
                me(http).getUsername()));
    }

    @PatchMapping("/admin/users/{id}/active")
    @RequireRole(Role.ADMIN)
    public UserDto setActive(@PathVariable Long id, @RequestBody SetActiveRequest req, HttpServletRequest http) {
        AppUser u = users.findById(id).orElseThrow(() -> ApiException.notFound("User " + id));
        if (u.getId().equals(me(http).getId())) {
            throw ApiException.badRequest("SELF", "You cannot deactivate yourself");
        }
        u.setActive(req.active());
        users.save(u);
        audit.log(req.active() ? "USER_ENABLED" : "USER_DISABLED", "USER", id, me(http).getUsername(), u.getUsername());
        return UserDto.of(u);
    }

    @GetMapping("/admin/settings")
    @RequireRole(Role.ADMIN)
    public Map<String, Object> settings() {
        return Map.of("risk", props.risk(), "otp", props.otp(), "hold", props.hold(), "ml", props.ml());
    }
}
