package com.fraudshield.security;

import com.fraudshield.domain.Role;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Roles allowed to call a controller or method. A method annotation overrides the
 * class one. ADMIN may call everything except the customer-only /api/me endpoints.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    Role[] value();
}
