package com.fraudshield.domain;

/**
 * CUSTOMER      bank customer: own account only (send money, OTP, warnings, report fraud)
 * ANALYST       bank fraud analyst: monitoring, alerts, review queue, freezing, escalation
 * INVESTIGATOR  police / cyber-cell investigator: read-only tracing, cases, case reports
 * ADMIN         bank administrator: users, simulation lab, models, audit, settings
 */
public enum Role { CUSTOMER, ANALYST, INVESTIGATOR, ADMIN }
