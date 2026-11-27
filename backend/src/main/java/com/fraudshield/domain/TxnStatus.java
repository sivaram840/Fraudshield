package com.fraudshield.domain;

/**
 * Transaction lifecycle. Money moves only on APPROVED -> COMPLETED, inside
 * ExecutionService. BLOCKED, REJECTED and EXPIRED are terminal and never execute.
 */
public enum TxnStatus { APPROVED, PENDING_MFA, HELD, COMPLETED, BLOCKED, REJECTED, EXPIRED, FAILED }
