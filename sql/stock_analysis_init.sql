-- 个股分析记录表
-- 状态机: PENDING -> RUNNING -> SUCCESS / FAILED
-- result_json: 完整研报 JSON
CREATE TABLE IF NOT EXISTS stock_analysis_record (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    stock_code      VARCHAR(16) NOT NULL COMMENT '股票代码 (含前缀 sh./sz./bj.)',
    stock_code_raw  VARCHAR(16) NOT NULL COMMENT '用户输入的原始代码',
    stock_name      VARCHAR(64) DEFAULT NULL COMMENT '股票名称 (分析完成后回填)',
    method          VARCHAR(32) NOT NULL DEFAULT 'full' COMMENT '分析方法',
    years           INT NOT NULL DEFAULT 2 COMMENT '财务历史年数',
    lite            TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否 lite 模式',
    quote_days      INT NOT NULL DEFAULT 60 COMMENT '行情回溯天数',
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
    error_message   VARCHAR(1024) DEFAULT NULL COMMENT '失败原因',
    current_price   DECIMAL(18,4) DEFAULT NULL COMMENT '当前价',
    verdict         VARCHAR(64) DEFAULT NULL COMMENT '紫苏叶判定结果',
    moat_score      INT DEFAULT NULL COMMENT '护城河打分 0-10',
    elapsed_ms      INT DEFAULT NULL COMMENT '分析耗时 (毫秒)',
    result_json     LONGTEXT DEFAULT NULL COMMENT '完整研报 JSON',
    submitted_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '提交时间',
    started_at      DATETIME(3) DEFAULT NULL COMMENT '开始分析时间',
    finished_at     DATETIME(3) DEFAULT NULL COMMENT '完成时间',
    INDEX idx_stock_code (stock_code),
    INDEX idx_status (status),
    INDEX idx_submitted_at (submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='个股分析记录';
