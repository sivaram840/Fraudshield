package com.fraudshield.web;

import com.fraudshield.domain.Role;
import com.fraudshield.security.AuthInterceptor;
import com.fraudshield.security.RequireRole;
import com.fraudshield.repo.AlertRepository;
import jakarta.servlet.http.HttpServletRequest;
import com.fraudshield.service.AccountService;
import com.fraudshield.web.dto.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
@RequireRole({Role.ANALYST, Role.INVESTIGATOR})
public class AccountController {
    private final AccountService accounts;
    private final AlertRepository alerts;

    public AccountController(AccountService accounts, AlertRepository alerts) {
        this.accounts = accounts;
        this.alerts = alerts;
    }

    @GetMapping
    public List<AccountDto> list() {
        return accounts.list().stream().map(AccountDto::of).toList();
    }

    @PostMapping
    @RequireRole(Role.ANALYST)
    @ResponseStatus(HttpStatus.CREATED)
    public AccountDto create(@Valid @RequestBody CreateAccountRequest req) {
        return AccountDto.of(accounts.create(req));
    }

    @GetMapping("/{accountNumber}")
    public AccountDto get(@PathVariable String accountNumber) {
        return AccountDto.of(accounts.get(accountNumber));
    }

    @PatchMapping("/{accountNumber}/status")
    @RequireRole(Role.ANALYST)
    public AccountDto status(@PathVariable String accountNumber, @Valid @RequestBody UpdateAccountStatusRequest req,
                             HttpServletRequest http) {
        return AccountDto.of(accounts.updateStatus(accountNumber, req.status(), req.note(),
                AuthInterceptor.current(http).getUsername()));
    }

    @GetMapping("/{accountNumber}/alerts")
    public List<AlertDto> alerts(@PathVariable String accountNumber) {
        return alerts.findByAccountIdOrderByCreatedAtDesc(accounts.get(accountNumber).getId()).stream()
                .map(AlertDto::of).toList();
    }
}
