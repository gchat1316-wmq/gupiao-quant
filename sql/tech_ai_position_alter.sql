-- 科技AI 持仓策略：浮盈加仓 + 移动止损 + 目标止盈
-- invest_stock_pool 新增持仓聚合与策略参数列；新增成交流水表 invest_position_fill。
-- 注：应用启动时 TechAiSchemaGuard 会自动建表/加列，此文件用于手工执行与留档。

-- pool_type 列在原表为 enum('quality','tech_vc','tech_ai')，需扩容以支持 'potential'
ALTER TABLE `invest_stock_pool`
  MODIFY COLUMN `pool_type` enum('quality','tech_vc','tech_ai','potential')
  NOT NULL COMMENT '质量优选/科技风投/科技AI/潜力监控';

ALTER TABLE `invest_stock_pool`
  -- 成交聚合（由流水重算）
  ADD COLUMN `entry_price` decimal(10,2) DEFAULT NULL COMMENT '首仓买入价' AFTER `alert_turnover_ratio_pct`,
  ADD COLUMN `position_lots` decimal(10,2) DEFAULT 0 COMMENT '当前持仓手数' AFTER `entry_price`,
  ADD COLUMN `avg_cost` decimal(10,2) DEFAULT NULL COMMENT '当前平均成本' AFTER `position_lots`,
  ADD COLUMN `total_invested` decimal(14,2) DEFAULT NULL COMMENT '当前持仓成本基础' AFTER `avg_cost`,
  ADD COLUMN `add_count` int DEFAULT 0 COMMENT '加仓次数' AFTER `total_invested`,
  ADD COLUMN `last_add_price` decimal(10,2) DEFAULT NULL COMMENT '最近一次买入价' AFTER `add_count`,
  ADD COLUMN `peak_price` decimal(10,2) DEFAULT NULL COMMENT '建仓后最高价' AFTER `last_add_price`,
  ADD COLUMN `stop_price` decimal(10,2) DEFAULT NULL COMMENT '当前移动止损价' AFTER `peak_price`,
  ADD COLUMN `realized_pnl` decimal(14,2) DEFAULT 0 COMMENT '已实现盈亏' AFTER `stop_price`,
  ADD COLUMN `position_state` varchar(20) DEFAULT 'none' COMMENT '仓位状态 none/holding/scaled/exited' AFTER `realized_pnl`,
  ADD COLUMN `take_profit_done` tinyint(1) DEFAULT 0 COMMENT '是否已执行目标减仓' AFTER `position_state`,
  ADD COLUMN `opened_at` datetime DEFAULT NULL COMMENT '首次建仓时间' AFTER `take_profit_done`,
  -- 策略参数
  ADD COLUMN `add_step_pct` decimal(6,2) DEFAULT 10.00 COMMENT '加仓步长(%)' AFTER `opened_at`,
  ADD COLUMN `trail_pct` decimal(6,2) DEFAULT 10.00 COMMENT '移动止损回撤(%)' AFTER `add_step_pct`,
  ADD COLUMN `add_size_schedule` varchar(50) DEFAULT '1,1,1' COMMENT '每档加仓手数表' AFTER `trail_pct`,
  ADD COLUMN `max_lots` decimal(10,2) DEFAULT NULL COMMENT '单票最大持仓手数' AFTER `add_size_schedule`,
  ADD COLUMN `take_profit_pct` decimal(6,2) DEFAULT 50.00 COMMENT '到目标价减仓比例(%)' AFTER `max_lots`,
  ADD COLUMN `breakeven_after_tp` tinyint(1) DEFAULT 1 COMMENT '止盈后是否止损上移保本' AFTER `take_profit_pct`,
  ADD COLUMN `time_stop_days` int DEFAULT NULL COMMENT '时间止损天数' AFTER `breakeven_after_tp`,
  ADD COLUMN `use_atr` tinyint(1) DEFAULT 0 COMMENT '是否启用ATR自适应' AFTER `time_stop_days`,
  ADD COLUMN `atr_period` int DEFAULT 14 COMMENT 'ATR周期' AFTER `use_atr`,
  ADD COLUMN `atr_add_mult` decimal(6,2) DEFAULT 1.00 COMMENT 'ATR加仓倍数' AFTER `atr_period`,
  ADD COLUMN `atr_trail_mult` decimal(6,2) DEFAULT 2.00 COMMENT 'ATR止损倍数' AFTER `atr_add_mult`;

CREATE TABLE IF NOT EXISTS `invest_position_fill` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `pool_id` int NOT NULL COMMENT 'invest_stock_pool.id',
  `stock_code` varchar(20) NOT NULL,
  `action` varchar(10) NOT NULL COMMENT 'open/add/reduce/clear',
  `price` decimal(10,2) NOT NULL,
  `lots` decimal(10,2) NOT NULL,
  `amount` decimal(14,2) DEFAULT NULL,
  `fee` decimal(10,2) DEFAULT NULL,
  `note` varchar(255) DEFAULT NULL,
  `filled_at` datetime NOT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_pool` (`pool_id`),
  KEY `idx_code` (`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='科技AI 持仓成交流水';
