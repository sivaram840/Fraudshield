package com.fraudshield.risk;

/**
 * One rule that fired.
 *
 * @param about which account the rule describes: "SENDER", "RECEIVER" or "TRANSACTION"
 */
public record RuleHit(String code, double weight, String about, String message) {
}
