-- V4: xiebo invest tables (watchlist + analysis_record)
CREATE TABLE IF NOT EXISTS invest_xiebo_watchlist (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    stock_code VARCHAR(20) NOT NULL UNIQUE,
    stock_name VARCHAR(64) NOT NULL,
    display_order INT DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_xiebo_watchlist_order (display_order, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS invest_xiebo_analysis_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    stock_code VARCHAR(20) NOT NULL,
    stock_name VARCHAR(64) NOT NULL,
    analysis_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'collecting',
    peg_value DECIMAL(10,2) DEFAULT NULL,
    peg_rating VARCHAR(32) DEFAULT NULL,
    conclusion VARCHAR(500) DEFAULT NULL,
    report_markdown LONGTEXT DEFAULT NULL,
    raw_snapshot_json LONGTEXT DEFAULT NULL,
    error_message VARCHAR(1000) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_xiebo_analysis_stock_date (stock_code, analysis_date),
    INDEX idx_xiebo_analysis_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- xiebo_recent module
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
    enabled         TINYINT(1) NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL DEFAULT '关注',
    status_updated_at DATETIME NULL,
    price_buy           DECIMAL(10,2) NULL,
    price_stop_loss     DECIMAL(10,2) NULL,
    price_add_position  DECIMAL(10,2) NULL,
    price_reduce_position DECIMAL(10,2) NULL,
    price_clear_position  DECIMAL(10,2) NULL,
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
