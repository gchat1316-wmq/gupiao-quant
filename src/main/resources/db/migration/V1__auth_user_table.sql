-- V1: auth_user (canonical form; reflects state after SchemaInitializer's ALTER-on-existing branches)
CREATE TABLE auth_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(50),
    password_hash VARCHAR(255),
    phone         VARCHAR(20)  UNIQUE,
    email         VARCHAR(255) UNIQUE,
    openid        VARCHAR(128) UNIQUE,
    unionid       VARCHAR(128) UNIQUE,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    disabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    last_login_at DATETIME,
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    avatar_url    VARCHAR(512),
    notify_wechat BOOLEAN      NOT NULL DEFAULT TRUE,
    notify_sms    BOOLEAN      NOT NULL DEFAULT FALSE,
    notify_phone  BOOLEAN      NOT NULL DEFAULT FALSE,
    serverchan_send_key VARCHAR(64) NULL COMMENT '默认 Server酱 SendKey',
    UNIQUE INDEX uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
