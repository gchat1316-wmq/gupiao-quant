-- ============================================================
-- v5 新增: 热点股票池
-- 背景: 龙头候选"入池"动作原来落到 invest_stock_pool(龙江投资股票池),
--       语义不对(枚举 pool_type 不含 hot),且两个池子的业务模型不同
--       (龙江=中长期持仓 / 热点=短线波段)。
-- 新建独立表 prosperity_stock_pool,所有字段扁平存,避免后续流水线重跑
-- 污染历史快照。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `prosperity_stock_pool` (
  `id` int NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) NOT NULL COMMENT '股票代码(唯一, 同股票多次入池只更新一行)',
  `stock_name` varchar(50) DEFAULT NULL,
  `status` varchar(20) DEFAULT 'watching' COMMENT 'watching/hit_target/stopped/expired',
  `pool_count` int NOT NULL DEFAULT '1' COMMENT '累计入池次数',
  `first_added_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '首次入池时间',
  `last_added_at` datetime DEFAULT NULL COMMENT '最近入池时间',
  `last_snap_date` date DEFAULT NULL COMMENT '最近入池的快照日期',
  `sector_name` varchar(64) DEFAULT NULL,
  `combined_score` decimal(8,2) DEFAULT NULL,
  `latest_price` decimal(10,2) DEFAULT NULL,
  `buy_left_price` decimal(10,2) DEFAULT NULL,
  `sell_target_1` decimal(10,2) DEFAULT NULL,
  `stop_loss_price` decimal(10,2) DEFAULT NULL,
  `core_position_pct` decimal(6,2) DEFAULT NULL,
  `tactical_position_pct` decimal(6,2) DEFAULT NULL,
  `action_signal` varchar(20) DEFAULT NULL,
  `memo` text COMMENT '入池理由(每次入池追加一条)',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pool_stock_code` (`stock_code`),
  KEY `idx_pool_last_added` (`last_added_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='热点选股股票池';
