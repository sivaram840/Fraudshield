package com.fraudshield.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("fraudshield")
public record FraudShieldProperties(Ml ml, Risk risk, Otp otp, Hold hold, boolean seedDemoData,
                                    List<String> corsOrigins) {

    public record Ml(String baseUrl, int timeoutMs, int historyHours) {
    }

    public record Risk(double weightTxn, double weightMule, double weightAnomaly, double weightRules,
                       double mfaThreshold, double holdThreshold, double blockThreshold) {
    }

    public record Otp(int ttlSeconds, int maxAttempts, boolean demoMode) {
    }

    public record Hold(int expiryHours) {
    }
}
