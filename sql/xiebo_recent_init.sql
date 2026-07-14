-- xiebo_recent_init.sql
-- 近期关注模块 — 3 张新表

CREATE TABLE IF NOT EXISTS invest_xiebo_recent_watch (
    stock_code          VARCHAR(16) PRIMARY KEY,
    stock_name          VARCHAR(64) NOT NULL,
    type                VARCHAR(16) NOT NULL COMMENT '科技AI|创新药|质量优选',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_admin_id BIGINT NULL,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_recent_watch_type (type),
    INDEX idx_recent_watch_created (created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS invest_xiebo_stock_note (
    stock_code          VARCHAR(16) PRIMARY KEY,
    note_html           LONGTEXT NULL,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by_admin_id BIGINT NULL,
    CONSTRAINT fk_stock_note_stock FOREIGN KEY (stock_code)
        REFERENCES invest_xiebo_recent_watch(stock_code) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_stock_subscription (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT NOT NULL,
    stock_code      VARCHAR(16) NOT NULL,
    enabled         TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否启用提醒',
    status          VARCHAR(16) NOT NULL DEFAULT '关注' COMMENT '关注|建仓|减仓|清仓',
    status_updated_at DATETIME NULL,
    price_buy           DECIMAL(10,2) NULL COMMENT '≤ 触发买入提醒',
    price_stop_loss     DECIMAL(10,2) NULL COMMENT '≤ 触发止损提醒',
    price_add_position  DECIMAL(10,2) NULL COMMENT '≤ 触发加仓提醒',
    price_reduce_position DECIMAL(10,2) NULL COMMENT '≥ 触发减仓提醒',
    price_clear_position  DECIMAL(10,2) NULL COMMENT '≥ 触发清仓提醒',
    alert_buy_triggered_at           DATETIME NULL,
    alert_stop_loss_triggered_at     DATETIME NULL,
    alert_add_position_triggered_at  DATETIME NULL,
    alert_reduce_position_triggered_at DATETIME NULL,
    alert_clear_position_triggered_at  DATETIME NULL,
    serverchan_send_key VARCHAR(64) NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version         INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_user_stock (user_id, stock_code),
    INDEX idx_user (user_id),
    INDEX idx_stock (stock_code),
    INDEX idx_enabled (enabled, stock_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
