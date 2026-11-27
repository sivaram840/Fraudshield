package com.fraudshield.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HoldExpiryJob {
    private final TransactionService transactions;

    public HoldExpiryJob(TransactionService transactions) {
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${fraudshield.hold.check-interval-ms:60000}")
    public void run() {
        transactions.expireStaleHolds();
    }
}
