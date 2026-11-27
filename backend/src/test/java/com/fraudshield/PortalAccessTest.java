package com.fraudshield;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudshield.domain.*;
import com.fraudshield.ml.MlClient;
import com.fraudshield.ml.MlScore;
import com.fraudshield.repo.AccountRepository;
import com.fraudshield.repo.AppUserRepository;
import com.fraudshield.security.AuthService;
import com.fraudshield.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Login, role-based access and the customer / analyst / investigator workflows. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortalAccessTest {
    static final String PW = "Passw0rd!";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired AppUserRepository users;
    @Autowired AccountService accountService;
    @Autowired AccountRepository accounts;
    @MockitoBean MlClient ml;

    Account custAccount;
    Account payee;
    String customer;

    @BeforeEach
    void setUp() {
        when(ml.score(any(), any(), any(), any(), any())).thenReturn(Optional.of(new MlScore(
                0.01, 0.01, 0.01, 0.01, 0.0, Map.of(), Map.of(), List.of(), "test")));
        for (Role r : List.of(Role.ADMIN, Role.ANALYST, Role.INVESTIGATOR)) {
            String name = "p-" + r.name().toLowerCase();
            if (!users.existsByUsername(name)) {
                auth.createUser(name, PW, name, r, null, "test");
            }
        }
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        custAccount = accountService.create("Portal Customer", "Delhi", null, new BigDecimal("200000"),
                Instant.now().minus(Duration.ofDays(800)));
        payee = accountService.create("Portal Payee", "Goa", null, new BigDecimal("0"),
                Instant.now().minus(Duration.ofDays(800)));
        customer = "cust-" + suffix;
        auth.createUser(customer, PW, "Portal Customer", Role.CUSTOMER, custAccount, "test");
    }

    String token(String user) {
        return "Bearer " + auth.login(user, PW).token();
    }

    MockHttpServletRequestBuilder as(String user, MockHttpServletRequestBuilder b) {
        return b.header("Authorization", token(user)).contentType(MediaType.APPLICATION_JSON);
    }

    JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @Test
    void loginAndSessions() throws Exception {
        JsonNode ok = body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"p-analyst\",\"password\":\"" + PW + "\"}")).andExpect(status().isOk()));
        assertThat(ok.at("/user/role").asText()).isEqualTo("ANALYST");
        assertThat(ok.get("token").asText()).hasSizeGreaterThan(30);

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"p-analyst\",\"password\":\"wrong-password\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard").header("Authorization", "Bearer forged")).andExpect(status().isUnauthorized());

        String t = "Bearer " + ok.get("token").asText();
        mvc.perform(post("/api/auth/logout").header("Authorization", t)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization", t)).andExpect(status().isUnauthorized());
    }

    @Test
    void eachRoleSeesOnlyItsFunctions() throws Exception {
        // customer: own portal only
        mvc.perform(as(customer, get("/api/me/overview"))).andExpect(status().isOk());
        mvc.perform(as(customer, get("/api/accounts"))).andExpect(status().isForbidden());
        mvc.perform(as(customer, get("/api/dashboard"))).andExpect(status().isForbidden());
        mvc.perform(as(customer, get("/api/graph"))).andExpect(status().isForbidden());
        // analyst: monitoring, not admin, not the customer portal
        mvc.perform(as("p-analyst", get("/api/dashboard"))).andExpect(status().isOk());
        mvc.perform(as("p-analyst", get("/api/admin/users"))).andExpect(status().isForbidden());
        mvc.perform(as("p-analyst", get("/api/me/overview"))).andExpect(status().isForbidden());
        // investigator: read-only tracing, cannot approve or freeze
        mvc.perform(as("p-investigator", get("/api/graph"))).andExpect(status().isOk());
        mvc.perform(as("p-investigator", get("/api/audit"))).andExpect(status().isOk());
        mvc.perform(as("p-investigator", post("/api/transactions/1/review").content("{\"action\":\"APPROVE\"}")))
                .andExpect(status().isForbidden());
        mvc.perform(as("p-investigator", patch("/api/accounts/" + payee.getAccountNumber() + "/status")
                .content("{\"status\":\"FROZEN\"}"))).andExpect(status().isForbidden());
        mvc.perform(as("p-investigator", get("/api/dashboard"))).andExpect(status().isForbidden());
        // admin: everything staff can do, plus users
        mvc.perform(as("p-admin", get("/api/admin/users"))).andExpect(status().isOk());
        mvc.perform(as("p-admin", get("/api/dashboard"))).andExpect(status().isOk());
    }

    @Test
    void customerSendsMoneyFromOwnAccountWithOtpAndSeesPlainStatuses() throws Exception {
        String t = token(customer);
        JsonNode p = body(mvc.perform(get("/api/me/payees/" + payee.getAccountNumber()).header("Authorization", t))
                .andExpect(status().isOk()));
        assertThat(p.get("maskedName").asText()).isEqualTo("P***** P****");

        // large first payment to a new payee -> step-up OTP
        JsonNode r = body(mvc.perform(post("/api/me/transfers").header("Authorization", t)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"toAccount\":\"" + payee.getAccountNumber() + "\",\"amount\":60000}"))
                .andExpect(status().isCreated()));
        assertThat(r.at("/transaction/status").asText()).isEqualTo("PENDING_MFA");
        assertThat(r.at("/transaction/statusText").asText()).isEqualTo("Waiting for your OTP");
        assertThat(r.toString()).doesNotContain("ruleScore", "muleProbability");   // no model internals
        long id = r.at("/transaction/id").asLong();

        JsonNode v = body(mvc.perform(post("/api/me/transfers/" + id + "/otp/verify").header("Authorization", t)
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + r.get("demoOtp").asText() + "\"}"))
                .andExpect(status().isOk()));
        assertThat(v.at("/transaction/status").asText()).isEqualTo("COMPLETED");
        assertThat(accounts.findById(custAccount.getId()).orElseThrow().getBalance()).isEqualByComparingTo("140000");

        // another customer cannot touch this transfer
        Account other = accountService.create("Other", "Delhi", null, new BigDecimal("10"), Instant.now());
        String otherUser = "other-" + UUID.randomUUID().toString().substring(0, 6);
        auth.createUser(otherUser, PW, "Other", Role.CUSTOMER, other, "test");
        mvc.perform(as(otherUser, post("/api/me/transfers/" + id + "/otp/resend"))).andExpect(status().isNotFound());
    }

    @Test
    void customerCanCancelAPendingTransferAndItNeverExecutes() throws Exception {
        String t = token(customer);
        JsonNode r = body(mvc.perform(post("/api/me/transfers").header("Authorization", t)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"toAccount\":\"" + payee.getAccountNumber() + "\",\"amount\":75000}")));
        long id = r.at("/transaction/id").asLong();
        JsonNode c = body(mvc.perform(post("/api/me/transfers/" + id + "/cancel").header("Authorization", t))
                .andExpect(status().isOk()));
        assertThat(c.get("status").asText()).isEqualTo("REJECTED");
        assertThat(accounts.findById(custAccount.getId()).orElseThrow().getBalance()).isEqualByComparingTo("200000");
    }

    @Test
    void customerReportReachesInvestigatorsAsACase() throws Exception {
        JsonNode r = body(mvc.perform(as(customer, post("/api/me/reports")
                .content("{\"type\":\"ASKED_TO_MOVE_MONEY\",\"details\":\"A caller promised me commission to forward money\"}")))
                .andExpect(status().isCreated()));
        String ref = r.get("caseReference").asText();
        JsonNode cases = body(mvc.perform(as("p-investigator", get("/api/cases"))).andExpect(status().isOk()));
        JsonNode mine = null;
        for (JsonNode c : cases) {
            if (c.get("reference").asText().equals(ref)) {
                mine = c;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("source").asText()).isEqualTo("CUSTOMER_REPORT");
        assertThat(mine.get("summary").asText()).contains("unwitting mule");

        JsonNode detail = body(mvc.perform(as("p-investigator", get("/api/cases/" + mine.get("id").asLong())))
                .andExpect(status().isOk()));
        assertThat(detail.at("/account/accountNumber").asText()).isEqualTo(custAccount.getAccountNumber());
        assertThat(detail.get("alerts").toString()).contains("CUSTOMER_REPORT");

        JsonNode upd = body(mvc.perform(as("p-investigator", patch("/api/cases/" + mine.get("id").asLong())
                .content("{\"status\":\"IN_PROGRESS\",\"assignedTo\":\"p-investigator\",\"findings\":\"Statement recorded\"}")))
                .andExpect(status().isOk()));
        assertThat(upd.get("status").asText()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void analystEscalatesAndAdminManagesUsers() throws Exception {
        mvc.perform(as("p-analyst", post("/api/cases").content(json.writeValueAsString(Map.of(
                "accountNumber", payee.getAccountNumber(), "title", "Fan-in mule pattern", "priority", "HIGH")))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.source").value("ANALYST_ESCALATION"));

        String name = "new-" + UUID.randomUUID().toString().substring(0, 6);
        mvc.perform(as("p-admin", post("/api/admin/users").content(json.writeValueAsString(Map.of(
                "username", name, "password", "Another#Pass1", "displayName", "Teller", "role", "CUSTOMER",
                "accountNumber", payee.getAccountNumber())))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.accountNumber").value(payee.getAccountNumber()));
        mvc.perform(as("p-admin", post("/api/admin/users").content(json.writeValueAsString(Map.of(
                "username", "bad-" + name, "password", "Another#Pass1", "displayName", "No account", "role", "CUSTOMER")))))
                .andExpect(status().isBadRequest());
    }
}
