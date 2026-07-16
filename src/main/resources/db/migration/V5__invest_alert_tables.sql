-- V5: invest_alert + invest_big_yang_signal
CREATE TABLE IF NOT EXISTS invest_alert (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    stock_code VARCHAR(20) NOT NULL,
    signal_type VARCHAR(30) NOT NULL,
    level INT DEFAULT NULL,
    title VARCHAR(200) DEFAULT NULL,
    content TEXT DEFAULT NULL,
    trigger_price DECIMAL(10,2) DEFAULT NULL,
    trigger_at DATETIME DEFAULT NULL,
    channels VARCHAR(100) DEFAULT NULL,
    pushed TINYINT DEFAULT 0,
    read_flag TINYINT DEFAULT 0,
    user_id INT DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_invest_alert_stock_signal (stock_code, signal_type, trigger_at),
    INDEX idx_invest_alert_signal_read (signal_type, read_flag, trigger_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS invest_big_yang_signal (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_pool_id INT DEFAULT NULL,
    source_pool_type VARCHAR(20) DEFAULT NULL,
    stock_code VARCHAR(20) NOT NULL,
    stock_name VARCHAR(64) NOT NULL,
    signal_status VARCHAR(20) NOT NULL,
    limit_up_streak INT NOT NULL,
    first_limit_up_date DATE NOT NULL,
    last_limit_up_date DATE NOT NULL,
    base_start_price DECIMAL(10,2) DEFAULT NULL,
    first_limit_up_open_price DECIMAL(10,2) DEFAULT NULL,
    first_limit_up_close_price DECIMAL(10,2) DEFAULT NULL,
    last_limit_up_close_price DECIMAL(10,2) DEFAULT NULL,
    trigger_price DECIMAL(10,2) DEFAULT NULL,
    trigger_date DATE DEFAULT NULL,
    status_reason VARCHAR(255) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_big_yang_stock_first_day (stock_code, first_limit_up_date),
    INDEX idx_big_yang_status_updated (signal_status, updated_at),
    INDEX idx_big_yang_source_pool (source_pool_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
