-- ============================================================
-- P0: 用户认证表
-- 依赖：无（独立迁移）
-- ============================================================

-- 用户表
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

-- 刷新令牌表（支持多设备登录 + 可撤销）
CREATE TABLE IF NOT EXISTS t_refresh_token (
    id          BIGSERIAL       PRIMARY KEY,
    user_id     BIGINT          NOT NULL REFERENCES t_user(id),
    token       VARCHAR(512)    NOT NULL UNIQUE,
    expires_at  TIMESTAMP       NOT NULL,
    revoked     BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_refresh_token_user
    ON t_refresh_token(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_token_expires
    ON t_refresh_token(expires_at);
CREATE INDEX IF NOT EXISTS idx_refresh_token_value
    ON t_refresh_token(token);
