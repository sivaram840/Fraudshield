package com.fraudshield.config;

import com.fraudshield.domain.Account;
import com.fraudshield.domain.Role;
import com.fraudshield.repo.AccountRepository;
import com.fraudshield.repo.AppUserRepository;
import com.fraudshield.security.AuthService;
import com.fraudshield.service.AccountService;
import com.fraudshield.service.SeedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Creates demo customers with a realistic past and one login per role the first time
 * the database is empty. All demo logins share DEMO_PASSWORD (local prototype only;
 * listed in the README).
 */
@Component
public class DataSeeder implements ApplicationRunner {
    public static final String DEMO_PASSWORD = "Demo@123";
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final String[][] CUSTOMERS = {
            {"Aarav Sharma", "Delhi"}, {"Diya Patel", "Gujarat"}, {"Kabir Singh", "Punjab"},
            {"Lakshmi Narayanan", "Tamil Nadu"}, {"Rohan Das", "West Bengal"}, {"Sneha Kulkarni", "Maharashtra"},
            {"Vikram Rao", "Karnataka"}, {"Fatima Khan", "Telangana"}, {"Neha Joshi", "Rajasthan"},
            {"Kiran Menon", "Kerala"}, {"Metro Supermarket", "Maharashtra"}, {"Sunrise Pharmacy", "Karnataka"}};
    private static final String[][] STAFF = {
            {"admin", "Bank Administrator", "ADMIN"},
            {"analyst", "Priya Raman (Fraud Analyst)", "ANALYST"},
            {"investigator", "Insp. Arvind Kumar (Cyber Crime Cell)", "INVESTIGATOR"}};

    private final FraudShieldProperties props;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final SeedService seed;
    private final AppUserRepository users;
    private final AuthService auth;

    public DataSeeder(FraudShieldProperties props, AccountRepository accounts, AccountService accountService,
                      SeedService seed, AppUserRepository users, AuthService auth) {
        this.props = props;
        this.accounts = accounts;
        this.accountService = accountService;
        this.seed = seed;
        this.users = users;
        this.auth = auth;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.seedDemoData()) {
            return;
        }
        if (accounts.count() == 0) {
            seedAccounts();
        }
        if (users.count() == 0) {
            seedUsers();
        }
    }

    private void seedAccounts() {
        Random rnd = new Random(42);
        Instant now = Instant.now();
        List<Account> created = new ArrayList<>();
        for (String[] c : CUSTOMERS) {
            created.add(accountService.create(c[0], c[1], "+9198" + (10000000 + rnd.nextInt(89999999)),
                    BigDecimal.valueOf(40000 + rnd.nextInt(260000)), now.minus(Duration.ofDays(200 + rnd.nextInt(1800)))));
        }
        for (int i = 0; i < 70; i++) {
            Account from = created.get(rnd.nextInt(10));
            Account to = created.get(rnd.nextInt(created.size()));
            if (from == to) {
                continue;
            }
            seed.history(from, to, BigDecimal.valueOf(200 + rnd.nextInt(6000)),
                    now.minus(Duration.ofMinutes(60 + rnd.nextInt(14 * 24 * 60))));
        }
        log.info("Seeded {} demo accounts with historical transactions", created.size());
    }

    private void seedUsers() {
        for (String[] s : STAFF) {
            auth.createUser(s[0], DEMO_PASSWORD, s[1], Role.valueOf(s[2]), null, "system");
        }
        for (Account a : accounts.findAll()) {
            for (int i = 0; i < 10; i++) {
                if (CUSTOMERS[i][0].equals(a.getHolderName())) {
                    String username = a.getHolderName().split(" ")[0].toLowerCase();
                    if (!users.existsByUsername(username)) {
                        auth.createUser(username, DEMO_PASSWORD, a.getHolderName(), Role.CUSTOMER, a, "system");
                    }
                }
            }
        }
        log.info("Seeded demo logins (password {}): admin, analyst, investigator and 10 customers", DEMO_PASSWORD);
    }
}
