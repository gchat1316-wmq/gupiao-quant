-- V7: prosperity_stock_pool (热点选股股票池)
-- Folds in owner_id + composite unique (ensureProsperityStockPoolOwnerId)
-- Source: SchemaInitializer.ensureProsperityStockPoolTable() + ensureProsperityStockPoolOwnerId()
-- Note: V7 starts with owner_id + composite unique (owner_id, stock_code) directly,
-- instead of the legacy uk_pool_stock_code single-column unique that ensureProsperityStockPoolOwnerId rewrites.

CREATE TABLE IF NOT EXISTS prosperity_stock_pool (
    id INT PRIMARY KEY AUTO_INCREMENT,
    stock_code VARCHAR(20) NOT NULL,
    stock_name VARCHAR(50) DEFAULT NULL,
    status VARCHAR(20) DEFAULT 'watching' COMMENT 'watching/hit_target/stopped/expired',
    pool_count INT NOT NULL DEFAULT 1 COMMENT '累计入池次数',
    first_added_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '首次入池时间',
    last_added_at DATETIME DEFAULT NULL COMMENT '最近入池时间',
    last_snap_date DATE DEFAULT NULL COMMENT '最近入池的快照日期',
    sector_name VARCHAR(64) DEFAULT NULL,
    combined_score DECIMAL(8,2) DEFAULT NULL,
    latest_price DECIMAL(10,2) DEFAULT NULL,
    buy_left_price DECIMAL(10,2) DEFAULT NULL,
    sell_target_1 DECIMAL(10,2) DEFAULT NULL,
    stop_loss_price DECIMAL(10,2) DEFAULT NULL,
    core_position_pct DECIMAL(6,2) DEFAULT NULL,
    tactical_position_pct DECIMAL(6,2) DEFAULT NULL,
    action_signal VARCHAR(20) DEFAULT NULL,
    memo TEXT COMMENT '入池理由(每次入池追加一条)',
    owner_id BIGINT DEFAULT NULL COMMENT '个人池隔离',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_owner_stock (owner_id, stock_code),
    KEY idx_pool_last_added (last_added_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='热点选股股票池';
