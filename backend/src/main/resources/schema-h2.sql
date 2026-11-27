-- FraudShield schema. Works on MySQL 8 and on H2 in MySQL mode (tests).
-- H2 variant used by the automated tests.

CREATE TABLE IF NOT EXISTS accounts (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_number VARCHAR(20)    NOT NULL UNIQUE,
    holder_name    VARCHAR(100)   NOT NULL,
    state          VARCHAR(50)    NOT NULL,
    phone          VARCHAR(20),
    balance        DECIMAL(15, 2) NOT NULL,
    status         VARCHAR(20)    NOT NULL,
    created_at     DATETIME(6)    NOT NULL,
    version        BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0)
);

CREATE TABLE IF NOT EXISTS transactions (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    reference        VARCHAR(24)    NOT NULL UNIQUE,
    idempotency_key  VARCHAR(80)    NOT NULL UNIQUE,
    from_account_id  BIGINT         NOT NULL,
    to_account_id    BIGINT         NOT NULL,
    amount           DECIMAL(15, 2) NOT NULL,
    description      VARCHAR(255),
    status           VARCHAR(20)    NOT NULL,
    decision         VARCHAR(10),
    risk_score       DECIMAL(6, 4),
    failure_reason   VARCHAR(255),
    origin           VARCHAR(20)    NOT NULL,
    created_at       DATETIME(6)    NOT NULL,
    executed_at      DATETIME(6),
    hold_expires_at  DATETIME(6),
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT fk_txn_from FOREIGN KEY (from_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_txn_to FOREIGN KEY (to_account_id) REFERENCES accounts (id),
    CONSTRAINT chk_amount_positive CHECK (amount > 0)
);
CREATE INDEX IF NOT EXISTS idx_txn_from_time ON transactions (from_account_id, created_at);
CREATE INDEX IF NOT EXISTS idx_txn_to_time ON transactions (to_account_id, created_at);
CREATE INDEX IF NOT EXISTS idx_txn_status ON transactions (status);

CREATE TABLE IF NOT EXISTS risk_assessments (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_id           BIGINT        NOT NULL UNIQUE,
    txn_fraud_probability    DECIMAL(6, 4),
    sender_mule_probability  DECIMAL(6, 4),
    receiver_mule_probability DECIMAL(6, 4),
    anomaly_score            DECIMAL(6, 4),
    rule_score               DECIMAL(6, 4) NOT NULL,
    final_score              DECIMAL(6, 4) NOT NULL,
    decision                 VARCHAR(10)   NOT NULL,
    ml_available             BOOLEAN       NOT NULL,
    model_version            VARCHAR(40),
    rules_fired              VARCHAR(1000),
    reasons                  VARCHAR(4000),
    created_at               DATETIME(6)   NOT NULL,
    CONSTRAINT fk_risk_txn FOREIGN KEY (transaction_id) REFERENCES transactions (id)
);

CREATE TABLE IF NOT EXISTS alerts (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_id  BIGINT,
    account_id      BIGINT,
    type            VARCHAR(30)  NOT NULL,
    severity        VARCHAR(10)  NOT NULL,
    message         VARCHAR(1000) NOT NULL,
    status          VARCHAR(10)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    resolved_at     DATETIME(6),
    resolution_note VARCHAR(500),
    CONSTRAINT fk_alert_txn FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_alert_account FOREIGN KEY (account_id) REFERENCES accounts (id)
);
CREATE INDEX IF NOT EXISTS idx_alert_status ON alerts (status);

CREATE TABLE IF NOT EXISTS otp_challenges (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_id  BIGINT      NOT NULL,
    code_hash       VARCHAR(64) NOT NULL,
    expires_at      DATETIME(6) NOT NULL,
    attempts        INT         NOT NULL DEFAULT 0,
    status          VARCHAR(10) NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    CONSTRAINT fk_otp_txn FOREIGN KEY (transaction_id) REFERENCES transactions (id)
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    action       VARCHAR(40)  NOT NULL,
    entity_type  VARCHAR(20)  NOT NULL,
    entity_id    BIGINT,
    actor        VARCHAR(40)  NOT NULL,
    details      VARCHAR(2000),
    created_at   DATETIME(6)  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_logs (created_at);

CREATE TABLE IF NOT EXISTS users (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    username       VARCHAR(50)  NOT NULL UNIQUE,
    password_hash  VARCHAR(200) NOT NULL,
    display_name   VARCHAR(100) NOT NULL,
    role           VARCHAR(20)  NOT NULL,
    account_id     BIGINT,
    active         BOOLEAN      NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    last_login_at  DATETIME(6),
    CONSTRAINT fk_user_account FOREIGN KEY (account_id) REFERENCES accounts (id)
);

CREATE TABLE IF NOT EXISTS auth_tokens (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    user_id     BIGINT      NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    expires_at  DATETIME(6) NOT NULL,
    CONSTRAINT fk_token_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE IF NOT EXISTS investigation_cases (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    reference   VARCHAR(20)   NOT NULL UNIQUE,
    account_id  BIGINT        NOT NULL,
    title       VARCHAR(200)  NOT NULL,
    summary     VARCHAR(2000),
    source      VARCHAR(30)   NOT NULL,
    priority    VARCHAR(10)   NOT NULL,
    status      VARCHAR(15)   NOT NULL,
    created_by  VARCHAR(50)   NOT NULL,
    assigned_to VARCHAR(50),
    findings    VARCHAR(4000),
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    CONSTRAINT fk_case_account FOREIGN KEY (account_id) REFERENCES accounts (id)
);
