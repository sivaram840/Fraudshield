package com.fraudshield.risk;

import com.fraudshield.config.FraudShieldProperties;
import com.fraudshield.domain.Account;
import com.fraudshield.domain.Decision;
import com.fraudshield.domain.Transaction;
import com.fraudshield.ml.MlClient;
import com.fraudshield.ml.MlScore;
import com.fraudshield.repo.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Combines the ML scores and the rule score into one risk score and a decision.
 *
 * final = w_txn * P(fraud) + w_mule * P(mule) + w_anomaly * anomaly + w_rules * rules
 *
 * Overrides, applied after the thresholds:
 *  1. Receiver account frozen                  -> BLOCK
 *  2. Rule score >= 0.80                       -> at least BLOCK
 *  3. Rule score >= 0.55                       -> at least HOLD
 *  4. Large new payee / draining / fan-in rcv  -> at least MFA (step-up authentication)
 *  5. ML service unavailable (fail-safe)       -> at least MFA, score = rule score
 */
@Service
public class RiskEngine {
    private final RuleEngine rules;
    private final MlClient ml;
    private final TransactionRepository txns;
    private final FraudShieldProperties props;

    public RiskEngine(RuleEngine rules, MlClient ml, TransactionRepository txns, FraudShieldProperties props) {
        this.rules = rules;
        this.ml = ml;
        this.txns = txns;
        this.props = props;
    }

    public record Assessment(double finalScore, Decision decision, RuleEngine.Result rules, Optional<MlScore> ml,
                             List<String> reasons) {
    }

    public Assessment assess(Account from, Account to, BigDecimal amount, Instant now) {
        RuleEngine.Result r = rules.evaluate(from, to, amount, now);
        // Network activity for graph features, plus 30 days of the two parties' own history so
        // long-standing relationships are not mistaken for brand-new counterparties.
        Map<Long, Transaction> history = new LinkedHashMap<>();
        txns.completedInvolving(List.of(from.getId(), to.getId()), now.minus(Duration.ofDays(30)))
                .forEach(t -> history.put(t.getId(), t));
        txns.completedSince(now.minus(Duration.ofHours(props.ml().historyHours())))
                .forEach(t -> history.put(t.getId(), t));
        Optional<MlScore> m = ml.score(from, to, amount, now, new ArrayList<>(history.values()));
        var w = props.risk();

        double score = m.map(s -> w.weightTxn() * s.txnFraudProbability()
                        + w.weightMule() * s.muleProbability()
                        + w.weightAnomaly() * s.anomalyScore()
                        + w.weightRules() * r.score())
                .orElse(r.score());
        score = Math.min(1.0, Math.max(0.0, score));

        Decision d = score >= w.blockThreshold() ? Decision.BLOCK
                : score >= w.holdThreshold() ? Decision.HOLD
                : score >= w.mfaThreshold() ? Decision.MFA
                : Decision.ALLOW;

        List<String> reasons = new ArrayList<>();
        if (r.hardBlock()) {
            d = Decision.BLOCK;
            reasons.add("Override: receiver account is frozen");
        } else if (r.score() >= 0.80) {
            d = max(d, Decision.BLOCK);
            reasons.add("Override: strong money-mule rule evidence (rule score " + round(r.score()) + ")");
        } else if (r.score() >= 0.55) {
            d = max(d, Decision.HOLD);
            reasons.add("Override: money-mule rule evidence requires analyst review (rule score " + round(r.score()) + ")");
        }
        if (r.fired("NEW_PAYEE_LARGE") || r.fired("DRAINS_ACCOUNT") || r.fired("RECEIVER_FAN_IN")) {
            if (d.ordinal() < Decision.MFA.ordinal()) {
                reasons.add("Step-up policy: large new payee, account-draining transfer or fan-in receiver requires OTP");
            }
            d = max(d, Decision.MFA);
        }
        if (m.isEmpty()) {
            d = max(d, Decision.MFA);
            reasons.add("ML service unavailable: decided on rules only, minimum MFA (fail-safe)");
        }
        r.hits().forEach(h -> reasons.add(h.message()));
        m.ifPresent(s -> reasons.addAll(s.reasons()));
        return new Assessment(score, d, r, m, reasons);
    }

    private static Decision max(Decision a, Decision b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private static String round(double v) {
        return String.format("%.2f", v);
    }
}
