-- V13: weekly opportunity slot tables (xiebo first per task brief)
-- Source: SchemaInitializer.ensureXieboWeeklyOpportunitySlotTable() + ensureWeeklyOpportunitySlotTable()
-- Note: xiebo version is created FIRST because both tables conceptually share the same name pattern.

CREATE TABLE IF NOT EXISTS xiebo_weekly_opportunity_slot (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    pool_type  VARCHAR(20)  NOT NULL,
    slot_index INT          NOT NULL,
    stock_code VARCHAR(16)  DEFAULT NULL,
    reason     VARCHAR(500) DEFAULT NULL,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_xiebo_pool_slot (pool_type, slot_index),
    KEY idx_xiebo_pool_type (pool_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='谢博投资 每周重点股票 3×3 卡片槽位';

CREATE TABLE IF NOT EXISTS invest_weekly_opportunity_slot (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    pool_type       VARCHAR(20)  NOT NULL,
    slot_index      INT          NOT NULL,
    stock_code      VARCHAR(16)  DEFAULT NULL,
    user_stock_name VARCHAR(100) DEFAULT NULL COMMENT '用户手工填的股票名（代码不在池中时兜底）',
    reason          VARCHAR(500) DEFAULT NULL,
    image_url       VARCHAR(500) DEFAULT NULL COMMENT 'slot 参考截图 URL',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pool_slot (pool_type, slot_index),
    KEY idx_pool_type (pool_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='每周机会点 3×3 卡片槽位';
