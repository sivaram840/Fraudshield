package com.fraudshield.service;

import com.fraudshield.domain.*;
import com.fraudshield.repo.AlertRepository;
import com.fraudshield.repo.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Builds the account-to-account money-flow graph, traces funds upstream and
 * downstream of an account, and classifies each account's likely role.
 */
@Service
public class GraphService {
    private static final EnumSet<TxnStatus> ATTEMPTED = EnumSet.of(TxnStatus.COMPLETED, TxnStatus.BLOCKED,
            TxnStatus.HELD, TxnStatus.EXPIRED, TxnStatus.REJECTED, TxnStatus.PENDING_MFA);

    private final TransactionRepository txns;
    private final AlertRepository alerts;

    public GraphService(TransactionRepository txns, AlertRepository alerts) {
        this.txns = txns;
        this.alerts = alerts;
    }

    /** Longest window the graph and trace endpoints accept (90 days). */
    public static final int MAX_HOURS = 24 * 90;

    public record Node(String id, String holder, String state, AccountStatus status, long ageDays, String role,
                       List<String> roleReasons, long openAlerts, int fanIn, int fanOut, BigDecimal inAmount,
                       BigDecimal outAmount, BigDecimal balance) {
    }

    public record Edge(String id, String source, String target, BigDecimal amount, Instant timestamp,
                       TxnStatus status, Decision decision, BigDecimal riskScore) {
    }

    public record Graph(List<Node> nodes, List<Edge> edges, Instant from, Instant to) {
    }

    public record Hop(String reference, String from, String to, String toHolder, BigDecimal amount, Instant at,
                      TxnStatus status, Decision decision) {
    }

    public record Trace(Node account, List<Hop> upstream, List<List<Hop>> downstreamPaths, BigDecimal totalIn,
                        BigDecimal totalForwarded, BigDecimal stoppedAmount, List<Node> endpoints,
                        List<Node> involved, String summary) {
    }

    @Transactional(readOnly = true)
    public Graph graph(String accountNumber, int hours, int depth) {
        Instant now = Instant.now();
        Instant since = now.minus(Duration.ofHours(hours));
        List<Transaction> window = txns.createdSince(since).stream()
                .filter(t -> t.getStatus() != TxnStatus.FAILED).toList();
        List<Transaction> chosen;
        if (accountNumber == null || accountNumber.isBlank()) {
            chosen = window.size() > 600 ? window.subList(window.size() - 600, window.size()) : window;
        } else {
            Set<String> reached = new HashSet<>(Set.of(accountNumber));
            Set<String> frontier = new HashSet<>(reached);
            for (int d = 0; d < depth && !frontier.isEmpty(); d++) {
                Set<String> next = new HashSet<>();
                for (Transaction t : window) {
                    String a = t.getFromAccount().getAccountNumber();
                    String b = t.getToAccount().getAccountNumber();
                    if (frontier.contains(a) && reached.add(b)) {
                        next.add(b);
                    }
                    if (frontier.contains(b) && reached.add(a)) {
                        next.add(a);
                    }
                }
                frontier = next;
            }
            chosen = window.stream().filter(t -> reached.contains(t.getFromAccount().getAccountNumber())
                    && reached.contains(t.getToAccount().getAccountNumber())).toList();
        }
        Map<String, Node> nodes = classify(chosen, window, since, now);
        List<Edge> edges = chosen.stream().map(t -> new Edge(t.getReference(), t.getFromAccount().getAccountNumber(),
                t.getToAccount().getAccountNumber(), t.getAmount(),
                t.getExecutedAt() != null ? t.getExecutedAt() : t.getCreatedAt(), t.getStatus(), t.getDecision(),
                t.getRiskScore())).toList();
        return new Graph(new ArrayList<>(nodes.values()), edges, since, now);
    }

    @Transactional(readOnly = true)
    public Trace trace(Account account, int hours) {
        Instant now = Instant.now();
        Instant since = now.minus(Duration.ofHours(hours));
        List<Transaction> window = txns.createdSince(since).stream()
                .filter(t -> ATTEMPTED.contains(t.getStatus())).toList();
        Map<String, List<Transaction>> outBy = new HashMap<>();
        for (Transaction t : window) {
            outBy.computeIfAbsent(t.getFromAccount().getAccountNumber(), k -> new ArrayList<>()).add(t);
        }
        String acct = account.getAccountNumber();
        List<Transaction> incoming = window.stream()
                .filter(t -> t.getToAccount().getAccountNumber().equals(acct) && t.getStatus() == TxnStatus.COMPLETED)
                .toList();
        Instant firstArrival = incoming.stream().map(Transaction::getExecutedAt).min(Comparator.naturalOrder())
                .orElse(since);

        List<List<Hop>> paths = new ArrayList<>();
        walk(acct, firstArrival, new ArrayList<>(), new HashSet<>(Set.of(acct)), outBy, paths);

        BigDecimal totalIn = sum(incoming);
        List<Transaction> outs = outBy.getOrDefault(acct, List.of());
        BigDecimal forwarded = sum(outs.stream().filter(t -> t.getStatus() == TxnStatus.COMPLETED).toList());
        BigDecimal stopped = sum(outs.stream().filter(t -> t.getStatus() != TxnStatus.COMPLETED).toList());

        Set<String> involvedIds = new LinkedHashSet<>();
        involvedIds.add(acct);
        incoming.forEach(t -> involvedIds.add(t.getFromAccount().getAccountNumber()));
        paths.forEach(p -> p.forEach(h -> involvedIds.add(h.to())));
        List<Transaction> related = window.stream().filter(t -> involvedIds.contains(t.getFromAccount().getAccountNumber())
                || involvedIds.contains(t.getToAccount().getAccountNumber())).toList();
        Map<String, Node> nodes = classify(related, window, since, now);

        Set<String> endIds = new LinkedHashSet<>();
        for (List<Hop> p : paths) {
            Hop last = p.get(p.size() - 1);
            if (last.status() == TxnStatus.COMPLETED) {
                endIds.add(last.to());
            }
        }
        List<Hop> upstream = incoming.stream().map(GraphService::hop).toList();
        Node self = nodes.get(acct);
        String summary = String.format(
                "%s received Rs %s from %d account(s); Rs %s moved on, Rs %s was stopped (blocked/held). "
                        + "%d downstream endpoint(s) identified for investigators.",
                acct, totalIn.toPlainString(), upstream.stream().map(Hop::from).distinct().count(),
                forwarded.toPlainString(), stopped.toPlainString(), endIds.size());
        return new Trace(self, upstream, paths, totalIn, forwarded, stopped,
                endIds.stream().map(nodes::get).filter(Objects::nonNull).toList(),
                involvedIds.stream().map(nodes::get).filter(Objects::nonNull).toList(), summary);
    }

    /** Time-respecting downstream walk: each hop must happen after the money arrived. */
    private void walk(String acct, Instant arrived, List<Hop> path, Set<String> seen,
                      Map<String, List<Transaction>> outBy, List<List<Hop>> paths) {
        List<Transaction> next = outBy.getOrDefault(acct, List.of()).stream()
                .filter(t -> !t.getCreatedAt().isBefore(arrived)).toList();
        if (next.isEmpty() || path.size() >= 6) {
            if (!path.isEmpty()) {
                paths.add(List.copyOf(path));
            }
            return;
        }
        for (Transaction t : next) {
            String to = t.getToAccount().getAccountNumber();
            path.add(hop(t));
            if (t.getStatus() != TxnStatus.COMPLETED || !seen.add(to)) {
                paths.add(List.copyOf(path));     // stopped here (blocked/held) or a cycle
            } else {
                walk(to, t.getExecutedAt(), path, seen, outBy, paths);
                seen.remove(to);
            }
            path.remove(path.size() - 1);
        }
    }

    private static Hop hop(Transaction t) {
        return new Hop(t.getReference(), t.getFromAccount().getAccountNumber(), t.getToAccount().getAccountNumber(),
                t.getToAccount().getHolderName(), t.getAmount(),
                t.getExecutedAt() != null ? t.getExecutedAt() : t.getCreatedAt(), t.getStatus(), t.getDecision());
    }

    private static BigDecimal sum(List<Transaction> list) {
        return list.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private record Stats(Account account, Set<String> senders, Set<String> receivers, BigDecimal in,
                         BigDecimal attemptedOut, long minHoldMinutes) {
    }

    /**
     * Role heuristics. They describe behaviour, not guilt: an investigator still decides.
     * LIKELY_UNWITTING_MULE   fan-in from 3+ accounts, tries to forward 70%+ within 4h, new or quiet account
     * MULE_PATTERN_REVIEW     the same behaviour on an established account (could be complicit)
     * LAYERING_HOP            receives from a mule-pattern account and forwards quickly
     * LIKELY_COLLECTOR        receives from mule-pattern accounts and keeps the money (lead to organiser)
     * POSSIBLE_VICTIM         established account that sent money into a mule-pattern account
     */
    Map<String, Node> classify(List<Transaction> shown, List<Transaction> window, Instant since, Instant now) {
        Map<String, Stats> stats = new LinkedHashMap<>();
        Set<Account> accounts = new LinkedHashSet<>();
        shown.forEach(t -> {
            accounts.add(t.getFromAccount());
            accounts.add(t.getToAccount());
        });
        for (Account a : accounts) {
            String id = a.getAccountNumber();
            List<Transaction> ins = window.stream().filter(t -> t.getToAccount().getAccountNumber().equals(id)
                    && t.getStatus() == TxnStatus.COMPLETED).toList();
            List<Transaction> outs = window.stream().filter(t -> t.getFromAccount().getAccountNumber().equals(id)
                    && ATTEMPTED.contains(t.getStatus())).toList();
            long hold = Long.MAX_VALUE;
            for (Transaction o : outs) {
                for (Transaction i : ins) {
                    if (!i.getExecutedAt().isAfter(o.getCreatedAt())) {
                        hold = Math.min(hold, Duration.between(i.getExecutedAt(), o.getCreatedAt()).toMinutes());
                    }
                }
            }
            Set<String> senders = new HashSet<>();
            ins.forEach(t -> senders.add(t.getFromAccount().getAccountNumber()));
            Set<String> receivers = new HashSet<>();
            outs.forEach(t -> receivers.add(t.getToAccount().getAccountNumber()));
            stats.put(id, new Stats(a, senders, receivers, sum(ins), sum(outs), hold));
        }

        Set<String> mules = new HashSet<>();
        stats.forEach((id, s) -> {
            if (s.senders().size() >= 3 && s.in().signum() > 0
                    && s.attemptedOut().compareTo(s.in().multiply(new BigDecimal("0.7"))) >= 0
                    && s.minHoldMinutes() <= 240) {
                mules.add(id);
            }
        });

        Map<String, Node> out = new LinkedHashMap<>();
        stats.forEach((id, s) -> {
            Account a = s.account();
            long age = Duration.between(a.getCreatedAt(), now).toDays();
            long prior = txns.completedBefore(a.getId(), since);
            List<String> why = new ArrayList<>();
            String role = "NORMAL";
            long fromMules = s.senders().stream().filter(mules::contains).count();
            long toMules = s.receivers().stream().filter(mules::contains).count();
            if (mules.contains(id)) {
                why.add("Received from " + s.senders().size() + " different accounts");
                why.add("Tried to move on Rs " + s.attemptedOut().toPlainString() + " of Rs " + s.in().toPlainString()
                        + " received, within " + s.minHoldMinutes() + " minutes");
                if (age < 90 || prior < 5) {
                    role = "LIKELY_UNWITTING_MULE";
                    why.add("Account is " + age + " days old with " + prior + " earlier transactions: typical of a "
                            + "recruited intermediary rather than the organiser");
                } else {
                    role = "MULE_PATTERN_REVIEW";
                    why.add("Established account (" + age + " days, " + prior
                            + " earlier transactions) showing mule behaviour: review for complicity");
                }
            } else if (fromMules > 0 && s.attemptedOut().compareTo(s.in().multiply(new BigDecimal("0.7"))) >= 0
                    && s.minHoldMinutes() <= 240) {
                role = "LAYERING_HOP";
                why.add("Received from a mule-pattern account and forwarded within " + s.minHoldMinutes() + " minutes");
            } else if (fromMules > 0) {
                role = "LIKELY_COLLECTOR";
                why.add("Collects money from " + fromMules + " mule-pattern account(s) and keeps it: "
                        + "downstream lead toward the organiser");
            } else if (toMules > 0) {
                role = prior >= 5 ? "POSSIBLE_VICTIM" : "SOURCE_REVIEW";
                why.add("Sent money into a mule-pattern account");
                if (prior >= 5) {
                    why.add("Has a normal history of " + prior + " earlier transactions: consistent with account takeover");
                }
            }
            long openAlerts = alerts.findByAccountIdOrderByCreatedAtDesc(a.getId()).stream()
                    .filter(al -> al.getStatus() == AlertStatus.OPEN).count();
            out.put(id, new Node(id, a.getHolderName(), a.getState(), a.getStatus(), age, role, why, openAlerts,
                    s.senders().size(), s.receivers().size(), s.in(), s.attemptedOut(), a.getBalance()));
        });
        return out;
    }
}
