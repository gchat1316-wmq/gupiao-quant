-- V6: stock_analysis_record (个股分析)
-- Folds in pdf_path (ensurePdfPathColumn), source_payload_json + report_html (ensureStockAnalysisUnifiedColumns)
-- Source: SchemaInitializer.ensureStockAnalysisTable() + ensurePdfPathColumn() + ensureStockAnalysisUnifiedColumns()

CREATE TABLE IF NOT EXISTS stock_analysis_record (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    stock_code      VARCHAR(16) NOT NULL,
    stock_code_raw  VARCHAR(16) NOT NULL,
    stock_name      VARCHAR(64) DEFAULT NULL,
    method          VARCHAR(32) NOT NULL DEFAULT 'full',
    years           INT NOT NULL DEFAULT 2,
    lite            TINYINT(1) NOT NULL DEFAULT 1,
    quote_days      INT NOT NULL DEFAULT 60,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    error_message   VARCHAR(1024) DEFAULT NULL,
    current_price   DECIMAL(18,4) DEFAULT NULL,
    verdict         VARCHAR(64) DEFAULT NULL,
    moat_score      INT DEFAULT NULL,
    elapsed_ms      INT DEFAULT NULL,
    result_json     LONGTEXT DEFAULT NULL,
    source_payload_json LONGTEXT DEFAULT NULL COMMENT '统一多源原始数据包 JSON',
    report_html     LONGTEXT DEFAULT NULL COMMENT '统一富报告 HTML',
    submitted_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at      DATETIME(3) DEFAULT NULL,
    finished_at     DATETIME(3) DEFAULT NULL,
    pdf_path        VARCHAR(255) DEFAULT NULL COMMENT '生成的 PDF 文件相对路径',
    INDEX idx_stock_code (stock_code),
    INDEX idx_status (status),
    INDEX idx_submitted_at (submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
