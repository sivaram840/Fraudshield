package com.fraudshield.web;

import com.fraudshield.domain.Role;
import com.fraudshield.security.AuthInterceptor;
import com.fraudshield.security.RequireRole;
import com.fraudshield.domain.AlertStatus;
import com.fraudshield.ml.MlClient;
import com.fraudshield.repo.AlertRepository;
import com.fraudshield.repo.AuditLogRepository;
import com.fraudshield.service.*;
import com.fraudshield.web.dto.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Alerts, audit log, money-flow graph, dashboard, model info and scenarios. */
@RestController
@RequestMapping("/api")
public class InsightController {
    private final AlertRepository alerts;
    private final AlertService alertService;
    private final AuditLogRepository audit;
    private final GraphService graph;
    private final AccountService accounts;
    private final DashboardService dashboard;
    private final ScenarioService scenarios;
    private final MlClient ml;

    public InsightController(AlertRepository alerts, AlertService alertService, AuditLogRepository audit,
                             GraphService graph, AccountService accounts, DashboardService dashboard,
                             ScenarioService scenarios, MlClient ml) {
        this.alerts = alerts;
        this.alertService = alertService;
        this.audit = audit;
        this.graph = graph;
        this.accounts = accounts;
        this.dashboard = dashboard;
        this.scenarios = scenarios;
        this.ml = ml;
    }

    @GetMapping("/alerts")
    @RequireRole({Role.ANALYST, Role.INVESTIGATOR})
    public PageDto<AlertDto> alerts(@RequestParam(required = false) AlertStatus status,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "100") int size) {
        var pr = PageRequest.of(page, Math.min(size, 500), Sort.by(Sort.Direction.DESC, "createdAt"));
        var p = status == null ? alerts.findAll(pr) : alerts.findByStatus(status, pr);
        return new PageDto<>(p.getContent().stream().map(AlertDto::of).toList(), page, size, p.getTotalElements());
    }

    @PostMapping("/alerts/{id}/resolve")
    @RequireRole(Role.ANALYST)
    public AlertDto resolve(@PathVariable Long id, @Valid @RequestBody(required = false) ResolveAlertRequest req) {
        return AlertDto.of(alertService.resolve(id, req == null ? null : req.note()));
    }

    @GetMapping("/audit")
    @RequireRole(Role.INVESTIGATOR)
    public PageDto<AuditDto> audit(@RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "100") int size) {
        var p = audit.findAllByOrderByCreatedAtDesc(PageRequest.of(page, Math.min(size, 500)));
        return new PageDto<>(p.getContent().stream().map(AuditDto::of).toList(), page, size, p.getTotalElements());
    }

    @GetMapping("/graph")
    @RequireRole({Role.ANALYST, Role.INVESTIGATOR})
    public GraphService.Graph graph(@RequestParam(required = false) String account,
                                    @RequestParam(defaultValue = "72") int hours,
                                    @RequestParam(defaultValue = "3") int depth) {
        return graph.graph(account, Math.min(hours, GraphService.MAX_HOURS), Math.min(depth, 6));
    }

    @GetMapping("/graph/trace/{accountNumber}")
    @RequireRole({Role.ANALYST, Role.INVESTIGATOR})
    public GraphService.Trace trace(@PathVariable String accountNumber, @RequestParam(defaultValue = "72") int hours) {
        return graph.trace(accounts.get(accountNumber), Math.min(hours, GraphService.MAX_HOURS));
    }

    @GetMapping("/dashboard")
    @RequireRole(Role.ANALYST)
    public DashboardService.Stats dashboard() {
        return dashboard.stats();
    }

    @GetMapping("/model")
    @RequireRole(Role.ANALYST)
    public Map<String, Object> model() {
        return ml.modelInfo().orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ML_UNAVAILABLE",
                "ML service is not reachable"));
    }

    @GetMapping("/scenarios")
    @RequireRole(Role.ADMIN)
    public List<ScenarioService.Info> scenarios() {
        return scenarios.catalogue();
    }

    @PostMapping("/scenarios/{name}")
    @RequireRole(Role.ADMIN)
    public ScenarioService.Report run(@PathVariable String name) {
        return scenarios.run(name);
    }
}
