package com.fraudshield.ml;

import com.fraudshield.config.FraudShieldProperties;
import com.fraudshield.domain.Account;
import com.fraudshield.domain.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Calls the Python ML service. Every failure (service down, timeout, bad
 * response) returns Optional.empty() so the risk engine can fall back to rules.
 */
@Component
public class MlClient {
    private static final Logger log = LoggerFactory.getLogger(MlClient.class);
    private final RestClient http;

    public MlClient(FraudShieldProperties props) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(props.ml().timeoutMs());
        f.setReadTimeout(props.ml().timeoutMs());
        this.http = RestClient.builder().baseUrl(props.ml().baseUrl()).requestFactory(f).build();
    }

    public Optional<MlScore> score(Account from, Account to, BigDecimal amount, Instant now,
                                   List<Transaction> recent) {
        var txn = Map.of(
                "sender", from.getAccountNumber(),
                "receiver", to.getAccountNumber(),
                "amount", amount,
                "type", "TRANSFER",
                "timestamp", now.toEpochMilli() / 1000.0,
                "senderBalance", from.getBalance(),
                "receiverBalance", to.getBalance());
        var edges = recent.stream().map(t -> Map.of(
                "src", t.getFromAccount().getAccountNumber(),
                "dst", t.getToAccount().getAccountNumber(),
                "amount", t.getAmount(),
                "timestamp", t.getExecutedAt().toEpochMilli() / 1000.0)).toList();
        try {
            return Optional.ofNullable(http.post().uri("/score")
                    .body(Map.of("transaction", txn, "recentTransactions", edges))
                    .retrieve().body(MlScore.class));
        } catch (Exception e) {
            log.warn("ML service unavailable, falling back to rules only: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> modelInfo() {
        try {
            return Optional.ofNullable(http.get().uri("/model-info").retrieve().body(Map.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public boolean healthy() {
        try {
            http.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
