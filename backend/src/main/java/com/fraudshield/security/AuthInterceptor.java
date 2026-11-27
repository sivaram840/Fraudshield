package com.fraudshield.security;

import com.fraudshield.domain.AppUser;
import com.fraudshield.domain.Role;
import com.fraudshield.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * Every /api call except login needs "Authorization: Bearer <token>". The signed-in
 * user is stored on the request; @RequireRole on the controller decides access.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String USER_ATTR = "fraudshield.user";
    private final AuthService auth;

    public AuthInterceptor(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if ("OPTIONS".equals(req.getMethod()) || !(handler instanceof HandlerMethod hm)) {
            return true;
        }
        if (req.getRequestURI().equals("/api/auth/login")) {
            return true;
        }
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "NOT_SIGNED_IN", "Sign in first");
        }
        AppUser user = auth.resolve(header.substring(7).trim())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED",
                        "Session expired; sign in again"));
        req.setAttribute(USER_ATTR, user);

        RequireRole rule = hm.getMethodAnnotation(RequireRole.class);
        if (rule == null) {
            rule = hm.getBeanType().getAnnotation(RequireRole.class);
        }
        if (rule == null) {
            return true;   // any signed-in user
        }
        boolean customerOnly = rule.value().length == 1 && rule.value()[0] == Role.CUSTOMER;
        boolean allowed = Arrays.asList(rule.value()).contains(user.getRole())
                || (user.getRole() == Role.ADMIN && !customerOnly);
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Your role (" + user.getRole() + ") cannot use this function");
        }
        return true;
    }

    public static AppUser current(HttpServletRequest req) {
        return (AppUser) req.getAttribute(USER_ATTR);
    }
}
