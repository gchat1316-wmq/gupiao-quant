CREATE TABLE IF NOT EXISTS invest_lynch_watchlist (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    stock_code VARCHAR(20) NOT NULL UNIQUE,
    stock_name VARCHAR(64) NOT NULL,
    display_order INT DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_lynch_watchlist_order (display_order, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS invest_lynch_analysis_record (
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
    INDEX idx_lynch_analysis_stock_date (stock_code, analysis_date),
    INDEX idx_lynch_analysis_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
