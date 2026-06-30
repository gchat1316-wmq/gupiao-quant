-- 每周机会点 3×3 卡片槽位表
-- 每个 pool_type 固定 9 个 slot（slotIndex 0~8）
-- SchemaInitializer 启动时会自动 ensure 此表；本脚本给 fresh DB 手动初始化用
CREATE TABLE IF NOT EXISTS invest_weekly_opportunity_slot (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    pool_type  VARCHAR(20)  NOT NULL,
    slot_index INT          NOT NULL,
    stock_code VARCHAR(16)  DEFAULT NULL,
    reason     VARCHAR(500) DEFAULT NULL,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pool_slot (pool_type, slot_index),
    KEY idx_pool_type (pool_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='每周机会点 3×3 卡片槽位';
