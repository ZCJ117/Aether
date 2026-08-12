-- ============================================================
-- P0: Spring Boot 自动初始化 — 认证与审计表
-- 通过 spring.sql.init.mode=always 自动执行
-- ============================================================

CREATE TABLE IF NOT EXISTS t_user (
    id          BIGSERIAL       PRIMARY KEY,
    username    VARCHAR(64)     NOT NULL UNIQUE,
    password    VARCHAR(256)    NOT NULL,
    email       VARCHAR(128),
    role        VARCHAR(32)     NOT NULL DEFAULT 'VIEWER',
    enabled     BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP       NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_user_username ON t_user(username);

CREATE TABLE IF NOT EXISTS t_refresh_token (
    id          BIGSERIAL       PRIMARY KEY,
    user_id     BIGINT          NOT NULL REFERENCES t_user(id),
    token       VARCHAR(512)    NOT NULL UNIQUE,
    expires_at  TIMESTAMP       NOT NULL,
    revoked     BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_refresh_token_user ON t_refresh_token(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_token_expires ON t_refresh_token(expires_at);

CREATE TABLE IF NOT EXISTS t_audit_log (
    id            BIGSERIAL       PRIMARY KEY,
    user_id       BIGINT,
    username      VARCHAR(64)     NOT NULL,
    action        VARCHAR(64)     NOT NULL,
    resource      VARCHAR(256),
    detail        TEXT,
    ip_address    VARCHAR(64),
    success       BOOLEAN         NOT NULL DEFAULT TRUE,
    error_message TEXT,
    created_at    TIMESTAMP       NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_audit_user_time ON t_audit_log(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_action ON t_audit_log(action);

-- ============================================================
-- D1: 异步委派表（hermes async_delegations 对齐）
-- ============================================================
CREATE TABLE IF NOT EXISTS t_async_delegation (
    id                   VARCHAR(64)  PRIMARY KEY,
    parent_session_id    VARCHAR(128) NOT NULL,
    parent_agent_id      VARCHAR(128),
    task_payload         TEXT         NOT NULL,
    tool_names           TEXT,
    state                VARCHAR(32)  NOT NULL,
    attempt_count        INTEGER      NOT NULL DEFAULT 1,
    result_summary       TEXT,
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    last_heartbeat_at    TIMESTAMP,
    completion_delivered BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_async_deleg_state
    ON t_async_delegation(state, updated_at);
CREATE INDEX IF NOT EXISTS idx_async_deleg_session
    ON t_async_delegation(parent_session_id);
