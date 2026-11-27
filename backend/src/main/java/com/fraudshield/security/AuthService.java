package com.fraudshield.security;

import com.fraudshield.domain.Account;
import com.fraudshield.domain.AppUser;
import com.fraudshield.domain.AuthToken;
import com.fraudshield.domain.Role;
import com.fraudshield.repo.AppUserRepository;
import com.fraudshield.repo.AuthTokenRepository;
import com.fraudshield.service.AuditService;
import com.fraudshield.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class AuthService {
    static final Duration SESSION = Duration.ofHours(8);
    private final SecureRandom random = new SecureRandom();
    private final AppUserRepository users;
    private final AuthTokenRepository tokens;
    private final PasswordHasher hasher;
    private final AuditService audit;

    public AuthService(AppUserRepository users, AuthTokenRepository tokens, PasswordHasher hasher, AuditService audit) {
        this.users = users;
        this.tokens = tokens;
        this.hasher = hasher;
        this.audit = audit;
    }

    public record Login(String token, AppUser user) {
    }

    @Transactional
    public Login login(String username, String password) {
        AppUser u = users.findByUsername(username.trim().toLowerCase()).orElse(null);
        if (u == null || !u.isActive() || !hasher.matches(password, u.getPasswordHash())) {
            audit.log("LOGIN_FAILED", "USER", u == null ? null : u.getId(), username, "Wrong username or password");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Wrong username or password");
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        AuthToken t = new AuthToken();
        t.setTokenHash(sha256(token));
        t.setUser(u);
        t.setCreatedAt(Instant.now());
        t.setExpiresAt(Instant.now().plus(SESSION));
        tokens.save(t);
        u.setLastLoginAt(Instant.now());
        audit.log("LOGIN", "USER", u.getId(), u.getUsername(), "Signed in as " + u.getRole());
        return new Login(token, u);
    }

    @Transactional(readOnly = true)
    public Optional<AppUser> resolve(String token) {
        return tokens.findByTokenHash(sha256(token))
                .filter(t -> t.getExpiresAt().isAfter(Instant.now()) && t.getUser().isActive())
                .map(AuthToken::getUser);
    }

    @Transactional
    public void logout(String token) {
        tokens.findByTokenHash(sha256(token)).ifPresent(tokens::delete);
    }

    @Transactional
    public AppUser createUser(String username, String password, String displayName, Role role, Account account,
                              String actor) {
        String name = username.trim().toLowerCase();
        if (users.existsByUsername(name)) {
            throw ApiException.conflict("USERNAME_TAKEN", "Username " + name + " is already taken");
        }
        if ((role == Role.CUSTOMER) != (account != null)) {
            throw ApiException.badRequest("ACCOUNT_LINK", "Customers need exactly one linked account; staff need none");
        }
        AppUser u = new AppUser();
        u.setUsername(name);
        u.setPasswordHash(hasher.hash(password));
        u.setDisplayName(displayName);
        u.setRole(role);
        u.setAccount(account);
        u.setCreatedAt(Instant.now());
        users.save(u);
        audit.log("USER_CREATED", "USER", u.getId(), actor, name + " (" + role + ")");
        return u;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
