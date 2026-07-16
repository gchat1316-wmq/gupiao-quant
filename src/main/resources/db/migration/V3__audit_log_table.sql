-- V3: audit_log + user_notification_log
CREATE TABLE audit_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT,
    action      VARCHAR(50)  NOT NULL,
    target      VARCHAR(255),
    detail      TEXT,
    ip          VARCHAR(45),
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_notification_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    channel     VARCHAR(16)  NOT NULL,
    stock_code  VARCHAR(16),
    type        VARCHAR(32)  NOT NULL,
    title       VARCHAR(200) NOT NULL,
    content     TEXT,
    status      VARCHAR(16)  NOT NULL,
    error       VARCHAR(500),
    sent_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_unl_user_time (user_id, sent_at),
    INDEX idx_unl_stock     (stock_code, sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
