-- 投资金句知识库表（首页轮播 + 管理后台编辑 + 学习搭子浏览）
-- 创建时间: 2026-06-30
-- 关联实体: com.quant.entity.InvestQuote
-- 关联接口: com.quant.repository.InvestQuoteRepository

CREATE TABLE IF NOT EXISTS invest_quote (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    content TEXT NOT NULL,
    author VARCHAR(100) DEFAULT NULL,
    source VARCHAR(200) DEFAULT NULL,
    tags VARCHAR(500) DEFAULT NULL,
    likes INT NOT NULL DEFAULT 0,
    imported_node_id BIGINT DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_invest_quote_created (created_at),
    INDEX idx_invest_quote_author (author)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;