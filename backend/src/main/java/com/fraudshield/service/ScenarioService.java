package com.fraudshield.service;

import com.fraudshield.config.DataSeeder;
import com.fraudshield.domain.Account;
import com.fraudshield.domain.Role;
import com.fraudshield.domain.Origin;
import com.fraudshield.domain.TxnStatus;
import com.fraudshield.repo.AccountRepository;
import com.fraudshield.security.AuthService;
import com.fraudshield.web.ApiException;
import com.fraudshield.web.dto.Dtos.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Controlled, simulated attack scenarios. Every transfer goes through the real
 * pipeline (risk engine, MFA, holds, execution), so the report shows exactly
 * what FraudShield decided.
 */
@Service
public class ScenarioService {
    private final AccountService accountService;
    private final AccountRepository accounts;
    private final TransactionService transactions;
    private final SeedService seed;
    private final AuthService auth;

    public ScenarioService(AccountService accountService, AccountRepository accounts,
                           TransactionService transactions, SeedService seed, AuthService auth) {
        this.auth = auth;
        this.accountService = accountService;
        this.accounts = accounts;
        this.transactions = transactions;
        this.seed = seed;
    }

    public record Step(String label, Long transactionId, String reference, String from, String to,
                       BigDecimal amount, String decision, String status, BigDecimal riskScore, String note) {
    }

    /** demoLogins: customer usernames created for this run (password: the demo password) to view the customer side. */
    public record Report(String scenario, String title, String description, List<Step> steps, String outcome,
                         String focusAccount, List<String> accounts, List<String> demoLogins) {
    }

    public record Info(String id, String title, String description) {
    }

    public List<Info> catalogue() {
        return List.of(
                new Info("mule-fan-in", "Money-mule fan-in (the problem statement)",
                        "Five victims in different states lose money through account takeover; an attacker routes it "
                                + "into one recruited intermediary who is told to forward it within the hour."),
                new Info("layering-chain", "Multi-stage layering chain",
                        "Money hops rapidly through a chain of accounts (A -> B -> C -> D -> E) to hide its origin."),
                new Info("normal-activity", "Normal customer activity",
                        "Ordinary small transfers between established customers. Expected: ALLOW."));
    }

    public Report run(String name) {
        return switch (name) {
            case "mule-fan-in" -> muleFanIn();
            case "layering-chain" -> layeringChain();
            case "normal-activity" -> normalActivity();
            default -> throw ApiException.notFound("Scenario " + name);
        };
    }

    private Report muleFanIn() {
        String run = UUID.randomUUID().toString().substring(0, 6);
        Random rnd = new Random();
        Instant now = Instant.now();
        String[][] people = {{"Anita Deshpande", "Maharashtra"}, {"Suresh Iyer", "Tamil Nadu"},
                {"Meera Reddy", "Karnataka"}, {"Rajesh Gupta", "Delhi"}, {"Sourav Banerjee", "West Bengal"}};
        List<Account> victims = new ArrayList<>();
        Account shop = accountService.create("Local Grocery Mart", "Maharashtra", null, new BigDecimal("50000"),
                now.minus(Duration.ofDays(900)));
        for (String[] p : people) {
            Account v = accountService.create(p[0], p[1], "+9190000" + (10000 + rnd.nextInt(89999)),
                    BigDecimal.valueOf(150000 + rnd.nextInt(250000)), now.minus(Duration.ofDays(400 + rnd.nextInt(1500))));
            for (int i = 0; i < 8; i++) {   // a normal past: small payments over the last month
                seed.history(v, shop, BigDecimal.valueOf(300 + rnd.nextInt(4000)),
                        now.minus(Duration.ofDays(2 + rnd.nextInt(28))).minus(Duration.ofMinutes(rnd.nextInt(600))));
            }
            victims.add(accounts.findById(v.getId()).orElseThrow());
        }
        Account mule = accountService.create("Ravi Kumar (recruited intermediary)", "Uttar Pradesh",
                "+919000099999", new BigDecimal("1200"), now.minus(Duration.ofDays(6)));
        Account shell = accountService.create("Quickpay Traders", "Jharkhand", null, new BigDecimal("500"),
                now.minus(Duration.ofDays(20)));
        Account cash = accountService.create("Cash-out Agent", "Jharkhand", null, new BigDecimal("100"),
                now.minus(Duration.ofDays(12)));

        List<Step> steps = new ArrayList<>();
        BigDecimal received = BigDecimal.ZERO;
        int i = 0;
        for (Account v : victims) {
            BigDecimal amt = v.getBalance().multiply(BigDecimal.valueOf(0.55 + rnd.nextDouble() * 0.25))
                    .setScale(2, RoundingMode.HALF_UP);
            Step s = transfer("Attacker (account takeover) moves money from " + v.getHolderName() + " to the intermediary",
                    v, mule, amt, "scn-" + run + "-v" + (i++), true);
            steps.add(s);
            if ("COMPLETED".equals(s.status())) {
                received = received.add(amt);
            }
        }
        Account muleNow = accounts.findById(mule.getId()).orElseThrow();
        BigDecimal forward = received.multiply(new BigDecimal("0.95")).setScale(2, RoundingMode.HALF_UP);
        String outcome;
        if (forward.signum() == 0) {
            outcome = "Every victim transfer was stopped before reaching the intermediary.";
        } else {
            Step fwd = transfer("Intermediary forwards the money 'as instructed' (promised a commission)",
                    muleNow, shell, forward.min(muleNow.getBalance()), "scn-" + run + "-fwd", false);
            steps.add(fwd);
            if ("COMPLETED".equals(fwd.status())) {
                Account shellNow = accounts.findById(shell.getId()).orElseThrow();
                steps.add(transfer("Second stage: shell account moves funds to a cash-out agent", shellNow, cash,
                        shellNow.getBalance().multiply(new BigDecimal("0.98")).setScale(2, RoundingMode.HALF_UP),
                        "scn-" + run + "-cash", false));
                outcome = "The onward transfer was NOT stopped. Review the thresholds.";
            } else {
                outcome = String.format("Rs %s reached the intermediary, but the onward transfer was %s, so the "
                                + "money stays in a traceable account. The intermediary was warned and flagged as a "
                                + "likely unwitting mule; the trace lists every victim for refunds.",
                        received.toPlainString(), fwd.status());
            }
        }
        long stoppedEarly = steps.stream().filter(s -> s.label().startsWith("Attacker")
                && !"COMPLETED".equals(s.status())).count();
        if (stoppedEarly > 0) {
            outcome += " " + stoppedEarly + " victim transfer(s) were already stopped at fan-in stage.";
        }
        List<String> ids = new ArrayList<>(victims.stream().map(Account::getAccountNumber).toList());
        ids.addAll(List.of(mule.getAccountNumber(), shell.getAccountNumber(), cash.getAccountNumber()));
        String muleLogin = "ravi-" + run;
        String victimLogin = victims.get(0).getHolderName().split(" ")[0].toLowerCase() + "-" + run;
        auth.createUser(muleLogin, DataSeeder.DEMO_PASSWORD, mule.getHolderName(), Role.CUSTOMER, mule, "scenario");
        auth.createUser(victimLogin, DataSeeder.DEMO_PASSWORD, victims.get(0).getHolderName(), Role.CUSTOMER,
                victims.get(0), "scenario");
        return new Report("mule-fan-in", catalogue().get(0).title(), catalogue().get(0).description(), steps,
                outcome, mule.getAccountNumber(), ids,
                List.of(muleLogin + " (the intermediary)", victimLogin + " (a victim)"));
    }

    private Report layeringChain() {
        String run = UUID.randomUUID().toString().substring(0, 6);
        Instant now = Instant.now();
        String[] names = {"Origin Holdings", "Layer One", "Layer Two", "Layer Three", "Final Collector"};
        List<Account> chain = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            chain.add(accountService.create(names[i], i % 2 == 0 ? "Gujarat" : "Rajasthan", null,
                    i == 0 ? new BigDecimal("500000") : new BigDecimal("1000"), now.minus(Duration.ofDays(10 + i))));
        }
        List<Step> steps = new ArrayList<>();
        BigDecimal amt = new BigDecimal("200000.00");
        String outcome = "The full chain executed. Review the thresholds.";
        for (int i = 0; i < chain.size() - 1; i++) {
            Account from = accounts.findById(chain.get(i).getId()).orElseThrow();
            Step s = transfer("Hop " + (i + 1) + ": " + names[i] + " -> " + names[i + 1], from,
                    chain.get(i + 1), amt, "scn-" + run + "-h" + i, true);
            steps.add(s);
            if (!"COMPLETED".equals(s.status())) {
                outcome = "Chain stopped at hop " + (i + 1) + " (" + s.status() + "): the layering was interrupted.";
                break;
            }
            amt = amt.multiply(new BigDecimal("0.98")).setScale(2, RoundingMode.HALF_UP);
        }
        return new Report("layering-chain", catalogue().get(1).title(), catalogue().get(1).description(), steps,
                outcome, chain.get(1).getAccountNumber(), chain.stream().map(Account::getAccountNumber).toList(), List.of());
    }

    private Report normalActivity() {
        String run = UUID.randomUUID().toString().substring(0, 6);
        Instant now = Instant.now();
        Random rnd = new Random();
        Account a = accountService.create("Priya Nair", "Kerala", null, new BigDecimal("80000"), now.minus(Duration.ofDays(1200)));
        Account b = accountService.create("Arjun Mehta", "Gujarat", null, new BigDecimal("60000"), now.minus(Duration.ofDays(800)));
        Account c = accountService.create("City Electricity Board", "Kerala", null, new BigDecimal("1000000"), now.minus(Duration.ofDays(3000)));
        for (int i = 0; i < 6; i++) {
            seed.history(a, b, BigDecimal.valueOf(500 + rnd.nextInt(3000)), now.minus(Duration.ofDays(3 + i * 4)));
            seed.history(b, a, BigDecimal.valueOf(500 + rnd.nextInt(3000)), now.minus(Duration.ofDays(4 + i * 4)));
        }
        List<Step> steps = new ArrayList<>();
        steps.add(transfer("Priya pays Arjun back for dinner", accounts.findById(a.getId()).orElseThrow(), b,
                new BigDecimal("1450.00"), "scn-" + run + "-n1", false));
        steps.add(transfer("Arjun pays the electricity bill", accounts.findById(b.getId()).orElseThrow(), c,
                new BigDecimal("2380.00"), "scn-" + run + "-n2", false));
        steps.add(transfer("Priya sends Arjun money for a shared trip", accounts.findById(a.getId()).orElseThrow(), b,
                new BigDecimal("6000.00"), "scn-" + run + "-n3", false));
        long allowed = steps.stream().filter(s -> "COMPLETED".equals(s.status())).count();
        return new Report("normal-activity", catalogue().get(2).title(), catalogue().get(2).description(), steps,
                allowed + " of " + steps.size() + " ordinary transfers completed without friction.",
                a.getAccountNumber(), List.of(a.getAccountNumber(), b.getAccountNumber(), c.getAccountNumber()), List.of());
    }

    /**
     * @param attackerHasOtp simulates an attacker who phished the victim's OTP, so MFA alone does not stop them
     */
    private Step transfer(String label, Account from, Account to, BigDecimal amount, String key, boolean attackerHasOtp) {
        TransferResponse r = transactions.create(new TransferRequest(from.getAccountNumber(), to.getAccountNumber(),
                amount, label, key), null, Origin.SCENARIO);
        TransactionDto t = r.transaction();
        String note = r.message();
        if (t.status() == TxnStatus.PENDING_MFA && r.demoOtp() != null) {
            if (attackerHasOtp) {
                t = transactions.verifyOtp(t.id(), r.demoOtp()).transaction();
                note = "MFA challenged; attacker used a phished OTP, so it " + t.status();
            } else {
                t = transactions.verifyOtp(t.id(), r.demoOtp()).transaction();
                note = "Account holder confirmed the OTP; " + t.status();
            }
        }
        return new Step(label, t.id(), t.reference(), t.fromAccount(), t.toAccount(), t.amount(),
                t.decision() == null ? null : t.decision().name(), t.status().name(), t.riskScore(), note);
    }
}
