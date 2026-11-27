package com.fraudshield.risk;

import com.fraudshield.domain.Account;
import com.fraudshield.domain.AccountStatus;
import com.fraudshield.domain.AlertStatus;
import com.fraudshield.domain.Severity;
import com.fraudshield.domain.Transaction;
import com.fraudshield.repo.AlertRepository;
import com.fraudshield.repo.TransactionRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Explainable money-mule rules evaluated against completed transactions in
 * the database. Each rule adds a weight; the rule score is the capped sum.
 */
@Component
public class RuleEngine {
    static final Duration DAY = Duration.ofHours(24);
    static final Duration RAPID_WINDOW = Duration.ofHours(2);
    static final BigDecimal RAPID_MIN_INFLOW = new BigDecimal("5000");
    static final BigDecimal DRAIN_MIN = new BigDecimal("10000");
    static final BigDecimal NEW_PAYEE_LARGE = new BigDecimal("50000");

    private final TransactionRepository txns;
    private final AlertRepository alerts;

    public RuleEngine(TransactionRepository txns, AlertRepository alerts) {
        this.txns = txns;
        this.alerts = alerts;
    }

    public record Result(double score, boolean hardBlock, List<RuleHit> hits) {
        public boolean fired(String code) {
            return hits.stream().anyMatch(h -> h.code().equals(code));
        }
    }

    public Result evaluate(Account from, Account to, BigDecimal amount, Instant now) {
        List<RuleHit> hits = new ArrayList<>();
        boolean hardBlock = false;

        // Fan-in into the receiver (this transfer included).
        Set<Long> receiverSenders = distinctSenders(to, now.minus(DAY));
        receiverSenders.add(from.getId());
        if (receiverSenders.size() >= 5) {
            hits.add(new RuleHit("RECEIVER_FAN_IN", 0.35, "RECEIVER",
                    "Receiver has been paid by " + receiverSenders.size() + " different accounts in 24h"));
        } else if (receiverSenders.size() >= 3) {
            hits.add(new RuleHit("RECEIVER_FAN_IN", 0.25, "RECEIVER",
                    "Receiver has been paid by " + receiverSenders.size() + " different accounts in 24h"));
        }

        // Fan-in into the sender: the sender collected from many accounts and is now paying out.
        Set<Long> senderSenders = distinctSenders(from, now.minus(DAY));
        if (senderSenders.size() >= 3) {
            hits.add(new RuleHit("SENDER_FAN_IN", 0.25, "SENDER",
                    "Sender received money from " + senderSenders.size() + " different accounts in the last 24h"));
        }

        // Rapid onward transfer: most of what just came in goes straight out.
        List<Transaction> recentIn = txns.completedIncoming(from.getId(), now.minus(RAPID_WINDOW));
        BigDecimal inflow = recentIn.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (inflow.compareTo(RAPID_MIN_INFLOW) >= 0
                && amount.compareTo(inflow.multiply(new BigDecimal("0.7"))) >= 0) {
            long minutes = Duration.between(recentIn.get(0).getExecutedAt(), now).toMinutes();
            hits.add(new RuleHit("RAPID_ONWARD", 0.35, "SENDER",
                    String.format("Forwarding %s%% of Rs %s received only %d minutes ago",
                            amount.multiply(BigDecimal.valueOf(100)).divide(inflow, 0, java.math.RoundingMode.HALF_UP),
                            inflow.toPlainString(), minutes)));
        }

        // Multi-stage chain: money reached the sender through other fast hops.
        int depth = upstreamDepth(from, now, 0);
        if (depth >= 2) {
            hits.add(new RuleHit("MULTI_STAGE_CHAIN", depth >= 3 ? 0.30 : 0.20, "SENDER",
                    "Funds reached the sender through a " + (depth + 1) + "-hop chain within 24h"));
        }

        // Newly opened account acting as a pass-through.
        long ageDays = Duration.between(from.getCreatedAt(), now).toDays();
        boolean passThrough = hits.stream().anyMatch(h -> h.code().equals("RAPID_ONWARD") || h.code().equals("SENDER_FAN_IN"));
        if (ageDays < 30 && passThrough) {
            hits.add(new RuleHit("NEW_ACCOUNT_PASS_THROUGH", 0.10, "SENDER",
                    "Sender account is only " + ageDays + " days old and is passing money through"));
        }

        // Account takeover signals on the sender.
        if (amount.compareTo(DRAIN_MIN) >= 0
                && amount.compareTo(from.getBalance().multiply(new BigDecimal("0.9"))) >= 0) {
            hits.add(new RuleHit("DRAINS_ACCOUNT", 0.15, "TRANSACTION",
                    "Transfer empties 90% or more of the sender's balance"));
        }
        if (amount.compareTo(NEW_PAYEE_LARGE) >= 0 && txns.completedBetween(from.getId(), to.getId()) == 0) {
            hits.add(new RuleHit("NEW_PAYEE_LARGE", 0.10, "TRANSACTION",
                    "Large first-ever payment to this receiver"));
        }
        if (txns.completedOutgoing(from.getId(), now.minus(Duration.ofHours(1))).size() >= 5) {
            hits.add(new RuleHit("HIGH_VELOCITY", 0.15, "SENDER", "Sender made 5 or more transfers in the last hour"));
        }

        // Counterparties already under suspicion.
        if (to.getStatus() == AccountStatus.FROZEN) {
            hardBlock = true;
            hits.add(new RuleHit("RECEIVER_FROZEN", 1.0, "RECEIVER", "Receiver account is frozen"));
        } else if (to.getStatus() == AccountStatus.UNDER_REVIEW || alerts.countByAccountIdAndStatusAndSeverityIn(
                to.getId(), AlertStatus.OPEN, List.of(Severity.HIGH, Severity.CRITICAL)) > 0) {
            hits.add(new RuleHit("RECEIVER_FLAGGED", 0.40, "RECEIVER",
                    "Receiver is under review or has open high-severity alerts"));
        }
        if (from.getStatus() == AccountStatus.UNDER_REVIEW) {
            hits.add(new RuleHit("SENDER_UNDER_REVIEW", 0.30, "SENDER", "Sender account is under review"));
        }

        double score = Math.min(1.0, hits.stream().mapToDouble(RuleHit::weight).sum());
        return new Result(score, hardBlock, hits);
    }

    private Set<Long> distinctSenders(Account account, Instant since) {
        Set<Long> ids = new HashSet<>();
        for (Transaction t : txns.completedIncoming(account.getId(), since)) {
            ids.add(t.getFromAccount().getId());
        }
        return ids;
    }

    /** Longest chain of completed transfers ending at the account, each hop within 24h of the next. */
    int upstreamDepth(Account account, Instant before, int level) {
        if (level >= 4) {
            return 0;
        }
        int best = 0;
        for (Transaction t : txns.completedIncoming(account.getId(), before.minus(DAY))) {
            if (t.getExecutedAt().isAfter(before)) {
                continue;
            }
            best = Math.max(best, 1 + upstreamDepth(t.getFromAccount(), t.getExecutedAt(), level + 1));
        }
        return best;
    }
}
