package com.fraudshield;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudshield.domain.*;
import com.fraudshield.ml.MlClient;
import com.fraudshield.ml.MlScore;
import com.fraudshield.repo.*;
import com.fraudshield.service.*;
import com.fraudshield.web.dto.Dtos.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransactionFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountService accountService;
    @Autowired AccountRepository accounts;
    @Autowired TransactionRepository txns;
    @Autowired RiskAssessmentRepository assessments;
    @Autowired AlertRepository alerts;
    @Autowired AuditLogRepository audit;
    @Autowired TransactionService transactions;
    @Autowired GraphService graph;
    @Autowired ScenarioService scenarios;
    @Autowired com.fraudshield.security.AuthService auth;
    @Autowired AppUserRepository users;
    @MockitoBean MlClient ml;
    String bearer;

    @BeforeEach
    void lowRiskMl() {
        ml(0.01, 0.01, 0.0);
        if (!users.existsByUsername("t-admin")) {
            auth.createUser("t-admin", "Passw0rd!", "Test admin", Role.ADMIN, null, "test");
        }
        bearer = "Bearer " + auth.login("t-admin", "Passw0rd!").token();
    }

    void ml(double txn, double mule, double anomaly) {
        when(ml.score(any(), any(), any(), any(), any())).thenReturn(Optional.of(new MlScore(
                txn, mule, mule, mule, anomaly, Map.of(), Map.of(), txn > 0.5 ? List.of("Transaction model: test") : List.of(), "test")));
    }

    Account account(String name, String balance, int ageDays) {
        return accountService.create(name, "Delhi", null, new BigDecimal(balance), Instant.now().minus(Duration.ofDays(ageDays)));
    }

    BigDecimal balance(Account a) {
        return accounts.findById(a.getId()).orElseThrow().getBalance();
    }

    TransferResponse send(Account from, Account to, String amount) {
        return transactions.create(new TransferRequest(from.getAccountNumber(), to.getAccountNumber(),
                new BigDecimal(amount), "test", UUID.randomUUID().toString()), null, Origin.API);
    }

    /** Sends and, if step-up authentication is requested, enters the OTP like the customer would. */
    TransactionDto sendCleared(Account from, Account to, String amount) {
        TransferResponse r = send(from, to, amount);
        if (r.transaction().status() == TxnStatus.PENDING_MFA) {
            return transactions.verifyOtp(r.transaction().id(), r.demoOtp()).transaction();
        }
        return r.transaction();
    }

    // ---- normal / decisions ------------------------------------------------

    @Test
    void largeFirstPaymentToNewPayeeRequiresOtp() {
        Account a = account("StepUp A", "200000", 900);
        Account b = account("StepUp B", "0", 900);
        TransferResponse r = send(a, b, "75000");
        assertThat(r.transaction().decision()).isEqualTo(Decision.MFA);
        assertThat(r.transaction().risk().reasons()).anyMatch(s -> s.startsWith("Step-up policy"));
    }

    @Test
    void normalTransferIsAllowedAndBalancesMoveExactlyOnce() {
        Account a = account("Normal A", "10000", 500);
        Account b = account("Normal B", "500", 500);
        TransferResponse r = send(a, b, "1234.56");
        assertThat(r.transaction().decision()).isEqualTo(Decision.ALLOW);
        assertThat(r.transaction().status()).isEqualTo(TxnStatus.COMPLETED);
        assertThat(balance(a)).isEqualByComparingTo("8765.44");
        assertThat(balance(b)).isEqualByComparingTo("1734.56");
        assertThat(assessments.findByTransactionId(r.transaction().id())).isPresent();
    }

    @Test
    void frozenReceiverIsBlockedAndNoMoneyMoves() {
        Account a = account("Block A", "10000", 500);
        Account b = account("Block B", "0", 500);
        accountService.updateStatus(b.getAccountNumber(), AccountStatus.FROZEN, "test", "test");
        TransferResponse r = send(a, b, "100");
        assertThat(r.transaction().decision()).isEqualTo(Decision.BLOCK);
        assertThat(r.transaction().status()).isEqualTo(TxnStatus.BLOCKED);
        assertThat(balance(a)).isEqualByComparingTo("10000");
        assertThat(alerts.findByTransactionId(r.transaction().id())).isNotEmpty();
    }

    @Test
    void blockedTransactionCanNeverBeExecutedOrReviewed() throws Exception {
        Account a = account("Block2 A", "10000", 500);
        Account b = account("Block2 B", "0", 500);
        accountService.updateStatus(b.getAccountNumber(), AccountStatus.FROZEN, "test", "test");
        Long id = send(a, b, "100").transaction().id();
        mvc.perform(post("/api/transactions/" + id + "/review").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"APPROVE\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/transactions/" + id + "/otp/verify").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"123456\"}")).andExpect(status().isConflict());
        assertThat(balance(a)).isEqualByComparingTo("10000");
    }

    // ---- MFA -----------------------------------------------------------------

    @Test
    void mfaWithCorrectOtpExecutes() {
        ml(0.5, 0.5, 0.5);   // 0.15 + 0.15 + 0.05 = 0.35 -> MFA
        Account a = account("Mfa A", "10000", 500);
        Account b = account("Mfa B", "0", 500);
        TransferResponse r = send(a, b, "2000");
        assertThat(r.transaction().status()).isEqualTo(TxnStatus.PENDING_MFA);
        assertThat(r.demoOtp()).matches("\\d{6}");
        assertThat(balance(a)).isEqualByComparingTo("10000");
        OtpResult o = transactions.verifyOtp(r.transaction().id(), r.demoOtp());
        assertThat(o.verified()).isTrue();
        assertThat(o.transaction().status()).isEqualTo(TxnStatus.COMPLETED);
        assertThat(balance(a)).isEqualByComparingTo("8000");
    }

    @Test
    void failedMfaThreeTimesRejectsAndRaisesAlert() {
        ml(0.5, 0.5, 0.5);
        Account a = account("MfaFail A", "10000", 500);
        Account b = account("MfaFail B", "0", 500);
        TransferResponse r = send(a, b, "2000");
        String wrong = r.demoOtp().equals("000000") ? "111111" : "000000";
        Long id = r.transaction().id();
        assertThat(transactions.verifyOtp(id, wrong).attemptsRemaining()).isEqualTo(2);
        assertThat(transactions.verifyOtp(id, wrong).attemptsRemaining()).isEqualTo(1);
        OtpResult last = transactions.verifyOtp(id, wrong);
        assertThat(last.transaction().status()).isEqualTo(TxnStatus.REJECTED);
        assertThat(balance(a)).isEqualByComparingTo("10000");
        assertThat(alerts.findByTransactionId(id)).anyMatch(al -> al.getType() == AlertType.MFA_FAILED);
        // even the right code is useless now
        assertThat(txns.findById(id).orElseThrow().getStatus()).isEqualTo(TxnStatus.REJECTED);
    }

    @Test
    void mlUnavailableFallsBackToRulesWithMinimumMfa() {
        when(ml.score(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        Account a = account("NoMl A", "10000", 500);
        Account b = account("NoMl B", "0", 500);
        TransferResponse r = send(a, b, "50");
        assertThat(r.transaction().decision()).isEqualTo(Decision.MFA);
        assertThat(r.transaction().risk().mlAvailable()).isFalse();
    }

    // ---- HOLD ----------------------------------------------------------------

    @Test
    void heldTransactionDoesNotExecuteAndExpiresUnresolved() {
        ml(0.95, 0.95, 0.9);  // 0.285 + 0.285 + 0.09 = 0.66 -> HOLD
        Account a = account("Hold A", "10000", 500);
        Account b = account("Hold B", "0", 500);
        TransferResponse r = send(a, b, "3000");
        assertThat(r.transaction().status()).isEqualTo(TxnStatus.HELD);
        assertThat(balance(a)).isEqualByComparingTo("10000");

        Transaction t = txns.findById(r.transaction().id()).orElseThrow();
        t.setHoldExpiresAt(Instant.now().minusSeconds(5));
        txns.save(t);
        assertThat(transactions.expireStaleHolds()).isGreaterThanOrEqualTo(1);
        assertThat(txns.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TxnStatus.EXPIRED);
        assertThat(balance(a)).isEqualByComparingTo("10000");
        assertThat(balance(b)).isEqualByComparingTo("0");
    }

    @Test
    void heldTransactionExecutesOnlyAfterAnalystApproval() {
        ml(0.95, 0.95, 0.9);
        Account a = account("Hold2 A", "10000", 500);
        Account b = account("Hold2 B", "0", 500);
        Long id = send(a, b, "3000").transaction().id();
        TransactionDto d = transactions.review(id, new ReviewRequest(ReviewAction.APPROVE, "verified by phone"), "analyst");
        assertThat(d.status()).isEqualTo(TxnStatus.COMPLETED);
        assertThat(balance(a)).isEqualByComparingTo("7000");

        Long id2 = send(a, b, "1000").transaction().id();
        assertThat(transactions.review(id2, new ReviewRequest(ReviewAction.REJECT, "fraud"), "analyst").status())
                .isEqualTo(TxnStatus.REJECTED);
        assertThat(balance(a)).isEqualByComparingTo("7000");
    }

    // ---- duplicates and consistency ----------------------------------------------

    @Test
    void duplicateRequestReturnsOriginalAndChargesOnce() throws Exception {
        Account a = account("Dup A", "10000", 500);
        Account b = account("Dup B", "0", 500);
        String body = json.writeValueAsString(Map.of("fromAccount", a.getAccountNumber(),
                "toAccount", b.getAccountNumber(), "amount", 700));
        String key = "dup-" + UUID.randomUUID();
        String first = mvc.perform(post("/api/transactions").header("Authorization", bearer).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/transactions").header("Authorization", bearer).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn().getResponse().getContentAsString();
        JsonNode f = json.readTree(first), s = json.readTree(second);
        assertThat(s.at("/transaction/id").asLong()).isEqualTo(f.at("/transaction/id").asLong());
        assertThat(s.get("duplicate").asBoolean()).isTrue();
        assertThat(balance(a)).isEqualByComparingTo("9300");

        String other = json.writeValueAsString(Map.of("fromAccount", a.getAccountNumber(),
                "toAccount", b.getAccountNumber(), "amount", 999));
        mvc.perform(post("/api/transactions").header("Authorization", bearer).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(other)).andExpect(status().isConflict());
    }

    @Test
    void concurrentDuplicatesCreateOneTransaction() throws Exception {
        Account a = account("ConDup A", "10000", 500);
        Account b = account("ConDup B", "0", 500);
        TransferRequest req = new TransferRequest(a.getAccountNumber(), b.getAccountNumber(),
                new BigDecimal("100"), "race", "race-" + UUID.randomUUID());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<TransferResponse>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fs.add(pool.submit(() -> transactions.create(req, null, Origin.API)));
        }
        Set<Long> ids = new HashSet<>();
        for (Future<TransferResponse> f : fs) {
            ids.add(f.get(30, TimeUnit.SECONDS).transaction().id());
        }
        pool.shutdown();
        assertThat(ids).hasSize(1);
        assertThat(balance(a)).isEqualByComparingTo("9900");
    }

    @Test
    void concurrentTransfersNeverOverdrawAndConserveMoney() throws Exception {
        Account a = account("Conc A", "10000", 500);
        Account b = account("Conc B", "0", 500);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            fs.add(pool.submit(() -> {
                try {
                    TransferResponse r = send(a, b, "1000");
                    return r.transaction().status() + " " + r.transaction().decision();
                } catch (RuntimeException e) {
                    return e.getClass().getSimpleName() + ": " + e.getMessage();
                }
            }));
        }
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : fs) {
            outcomes.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        System.out.println("CONCURRENCY OUTCOMES " + outcomes);
        assertThat(balance(a)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(balance(a).add(balance(b))).isEqualByComparingTo("10000");
        assertThat(balance(b)).isEqualByComparingTo("10000");   // exactly ten transfers succeeded
    }

    @Test
    void validationErrors() throws Exception {
        Account a = account("Val A", "100", 500);
        Account b = account("Val B", "0", 500);
        String base = "{\"fromAccount\":\"%s\",\"toAccount\":\"%s\",\"amount\":%s}";
        mvc.perform(post("/api/transactions").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(base, a.getAccountNumber(), b.getAccountNumber(), "10")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(post("/api/transactions").header("Authorization", bearer).header("Idempotency-Key", "v1").contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(base, a.getAccountNumber(), b.getAccountNumber(), "-5")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/transactions").header("Authorization", bearer).header("Idempotency-Key", "v2").contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(base, a.getAccountNumber(), a.getAccountNumber(), "5")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SAME_ACCOUNT"));
        mvc.perform(post("/api/transactions").header("Authorization", bearer).header("Idempotency-Key", "v3").contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(base, a.getAccountNumber(), b.getAccountNumber(), "5000")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    // ---- money-mule patterns -------------------------------------------------------

    @Test
    void fanInFollowedByRapidOnwardTransferIsBlockedAndMuleIsWarned() {
        Account mule = account("Mule M", "1200", 5);
        Account out = account("Mule Out", "0", 20);
        for (int i = 0; i < 4; i++) {
            Account victim = account("Victim " + i, "100000", 900);
            // the attacker has phished the OTP, so step-up MFA alone does not stop the inflow
            assertThat(sendCleared(victim, mule, "30000").status()).isEqualTo(TxnStatus.COMPLETED);
        }
        TransferResponse fwd = send(mule, out, "115000");
        assertThat(fwd.transaction().decision()).isEqualTo(Decision.BLOCK);
        assertThat(fwd.transaction().risk().rulesFired()).contains("SENDER_FAN_IN", "RAPID_ONWARD");
        assertThat(balance(out)).isEqualByComparingTo("0");
        assertThat(balance(mule)).isEqualByComparingTo("121200");
        assertThat(accounts.findById(mule.getId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.UNDER_REVIEW);
        assertThat(alerts.findByAccountIdOrderByCreatedAtDesc(mule.getId()))
                .extracting(Alert::getType).contains(AlertType.MULE_WARNING, AlertType.RAPID_ONWARD_TRANSFER, AlertType.FAN_IN);

        GraphService.Trace trace = graph.trace(accounts.findById(mule.getId()).orElseThrow(), 24);
        assertThat(trace.account().role()).isEqualTo("LIKELY_UNWITTING_MULE");
        assertThat(trace.upstream()).hasSize(4);
        assertThat(trace.stoppedAmount()).isEqualByComparingTo("115000");
    }

    @Test
    void receiverFanInRuleFiresOnThirdDistinctSender() {
        Account r = account("FanIn R", "0", 400);
        TransferResponse last = null;
        for (int i = 0; i < 3; i++) {
            last = send(account("FanIn S" + i, "5000", 400), r, "100");
        }
        assertThat(last.transaction().risk().rulesFired()).contains("RECEIVER_FAN_IN");
    }

    @Test
    void multiStageChainIsDetected() {
        Account a = account("Chain A", "9000", 400);
        Account b = account("Chain B", "0", 400);
        Account c = account("Chain C", "0", 400);
        Account d = account("Chain D", "0", 400);
        send(a, b, "3000");
        send(b, c, "2900");
        TransferResponse r = send(c, d, "2800");
        assertThat(r.transaction().risk().rulesFired()).contains("MULTI_STAGE_CHAIN");
    }

    @Test
    void muleScenarioStopsTheOnwardTransfer() {
        ScenarioService.Report rep = scenarios.run("mule-fan-in");
        ScenarioService.Step fwd = rep.steps().stream().filter(s -> s.label().startsWith("Intermediary")).findFirst()
                .orElseThrow();
        assertThat(fwd.status()).isIn("BLOCKED", "HELD");
        assertThat(rep.steps()).noneMatch(s -> s.label().startsWith("Second stage"));
        assertThat(audit.countByAction("RISK_DECISION")).isGreaterThan(0);
    }

    @Test
    void normalScenarioIsFrictionless() {
        ScenarioService.Report rep = scenarios.run("normal-activity");
        assertThat(rep.steps()).allMatch(s -> s.status().equals("COMPLETED"));
    }
}
