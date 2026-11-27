package com.fraudshield.service;

import com.fraudshield.domain.*;
import com.fraudshield.ml.MlClient;
import com.fraudshield.repo.AccountRepository;
import com.fraudshield.repo.AlertRepository;
import com.fraudshield.repo.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DashboardService {
    private final TransactionRepository txns;
    private final AccountRepository accounts;
    private final AlertRepository alerts;
    private final MlClient ml;

    public DashboardService(TransactionRepository txns, AccountRepository accounts, AlertRepository alerts, MlClient ml) {
        this.txns = txns;
        this.accounts = accounts;
        this.alerts = alerts;
        this.ml = ml;
    }

    public record HourBucket(String hour, long completed, long mfa, long held, long blocked) {
    }

    public record RiskyAccount(String accountNumber, String holder, String state, AccountStatus status,
                               long openAlerts, String topAlert) {
    }

    public record Stats(long accounts, long transactions, Map<TxnStatus, Long> byStatus, Map<Decision, Long> byDecision,
                        BigDecimal completedVolume, BigDecimal preventedVolume, long openAlerts,
                        Map<Severity, Long> openAlertsBySeverity, Map<String, Long> riskBands,
                        List<HourBucket> last24h, List<RiskyAccount> riskyAccounts, boolean mlOnline) {
    }

    @Transactional(readOnly = true)
    public Stats stats() {
        List<Transaction> all = txns.findAll();
        Map<TxnStatus, Long> byStatus = new EnumMap<>(TxnStatus.class);
        Map<Decision, Long> byDecision = new EnumMap<>(Decision.class);
        for (TxnStatus s : TxnStatus.values()) {
            byStatus.put(s, 0L);
        }
        for (Decision d : Decision.values()) {
            byDecision.put(d, 0L);
        }
        Map<String, Long> bands = new LinkedHashMap<>();
        bands.put("0.0-0.3", 0L);
        bands.put("0.3-0.55", 0L);
        bands.put("0.55-0.8", 0L);
        bands.put("0.8-1.0", 0L);
        BigDecimal completed = BigDecimal.ZERO;
        BigDecimal prevented = BigDecimal.ZERO;
        for (Transaction t : all) {
            byStatus.merge(t.getStatus(), 1L, Long::sum);
            if (t.getDecision() != null) {
                byDecision.merge(t.getDecision(), 1L, Long::sum);
            }
            if (t.getRiskScore() != null) {
                double r = t.getRiskScore().doubleValue();
                bands.merge(r < 0.3 ? "0.0-0.3" : r < 0.55 ? "0.3-0.55" : r < 0.8 ? "0.55-0.8" : "0.8-1.0", 1L, Long::sum);
            }
            if (t.getStatus() == TxnStatus.COMPLETED) {
                completed = completed.add(t.getAmount());
            }
            if (EnumSet.of(TxnStatus.BLOCKED, TxnStatus.REJECTED, TxnStatus.EXPIRED, TxnStatus.HELD).contains(t.getStatus())) {
                prevented = prevented.add(t.getAmount());
            }
        }

        Instant start = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(23));
        Map<Instant, long[]> hours = new LinkedHashMap<>();
        for (int i = 0; i < 24; i++) {
            hours.put(start.plus(Duration.ofHours(i)), new long[4]);
        }
        for (Transaction t : all) {
            long[] b = hours.get(t.getCreatedAt().truncatedTo(ChronoUnit.HOURS));
            if (b == null || t.getDecision() == null) {
                continue;
            }
            switch (t.getDecision()) {
                case ALLOW -> b[0]++;
                case MFA -> b[1]++;
                case HOLD -> b[2]++;
                case BLOCK -> b[3]++;
            }
        }
        List<HourBucket> last24h = hours.entrySet().stream()
                .map(e -> new HourBucket(e.getKey().toString().substring(11, 16), e.getValue()[0], e.getValue()[1],
                        e.getValue()[2], e.getValue()[3])).toList();

        List<Alert> open = alerts.findAll().stream().filter(a -> a.getStatus() == AlertStatus.OPEN).toList();
        Map<Severity, Long> bySev = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) {
            bySev.put(s, 0L);
        }
        open.forEach(a -> bySev.merge(a.getSeverity(), 1L, Long::sum));
        Map<Account, List<Alert>> perAccount = open.stream().filter(a -> a.getAccount() != null)
                .collect(Collectors.groupingBy(Alert::getAccount));
        List<RiskyAccount> risky = perAccount.entrySet().stream()
                .sorted((x, y) -> Integer.compare(y.getValue().size(), x.getValue().size()))
                .limit(8)
                .map(e -> new RiskyAccount(e.getKey().getAccountNumber(), e.getKey().getHolderName(),
                        e.getKey().getState(), e.getKey().getStatus(), e.getValue().size(),
                        e.getValue().stream().max(Comparator.comparing(Alert::getSeverity)).map(a -> a.getType().name())
                                .orElse(null)))
                .toList();

        return new Stats(accounts.count(), all.size(), byStatus, byDecision, completed, prevented, open.size(),
                bySev, bands, last24h, risky, ml.healthy());
    }
}
