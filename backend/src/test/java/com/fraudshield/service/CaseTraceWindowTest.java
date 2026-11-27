package com.fraudshield.service;

import com.fraudshield.domain.InvestigationCase;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** A case file must keep its evidence as it ages: the trace window is anchored to when the case was opened. */
class CaseTraceWindowTest {

    private static InvestigationCase openedAgo(Duration age) {
        InvestigationCase c = new InvestigationCase();
        c.setCreatedAt(Instant.now().minus(age));
        return c;
    }

    @Test
    void newCaseUsesFourteenDays() {
        assertThat(CaseService.traceHours(openedAgo(Duration.ZERO))).isEqualTo(14 * 24);
    }

    @Test
    void monthOldCaseStillCoversTheTransfersBeforeItWasOpened() {
        assertThat(CaseService.traceHours(openedAgo(Duration.ofDays(30)))).isBetween(44 * 24 - 1, 44 * 24);
    }

    @Test
    void windowIsCappedAtTheGraphMaximum() {
        assertThat(CaseService.traceHours(openedAgo(Duration.ofDays(400)))).isEqualTo(GraphService.MAX_HOURS);
    }
}
