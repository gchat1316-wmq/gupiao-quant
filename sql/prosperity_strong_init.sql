-- ============================================================
-- 高景气强势股选股 - 数据表初始化脚本
-- 来源: docs/PRD-高景气强势股选股.md
-- 执行环境: MySQL 5.7+ / 8.x
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 每日热门板块
-- ----------------------------
DROP TABLE IF EXISTS `prosperity_hot_sector`;
CREATE TABLE `prosperity_hot_sector` (
  `id` int NOT NULL AUTO_INCREMENT,
  `snap_date` date NOT NULL,
  `sector_code` varchar(32) DEFAULT NULL COMMENT '板块代码,如东方财富 BK0xxx',
  `sector_name` varchar(64) NOT NULL,
  `rank_no` int NOT NULL,
  `change_1d` decimal(8,4) DEFAULT NULL,
  `change_5d` decimal(8,4) DEFAULT NULL,
  `change_20d` decimal(8,4) DEFAULT NULL,
  `capital_inflow_5d` decimal(20,2) DEFAULT NULL COMMENT '5日主力净流入(元)',
  `persistence_days` int DEFAULT NULL COMMENT '近10日红盘天数',
  `score` decimal(8,2) DEFAULT NULL COMMENT '板块综合评分0-100',
  `ai_narrative` text COMMENT 'AI 板块叙事',
  `data_source` varchar(20) DEFAULT 'eastmoney',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_date_sector` (`snap_date`, `sector_name`),
  KEY `idx_date_rank` (`snap_date`, `rank_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='强势股流水线-每日热门板块快照';

-- ----------------------------
-- 2. 板块龙头候选
-- ----------------------------
DROP TABLE IF EXISTS `prosperity_leader_candidate`;
CREATE TABLE `prosperity_leader_candidate` (
  `id` int NOT NULL AUTO_INCREMENT,
  `snap_date` date NOT NULL,
  `sector_id` int NOT NULL,
  `sector_name` varchar(64) NOT NULL,
  `stock_code` varchar(20) NOT NULL,
  `stock_name` varchar(50) DEFAULT NULL,
  `leader_score` decimal(8,2) DEFAULT NULL,
  `ytd_change` decimal(8,4) DEFAULT NULL,
  `change_5d` decimal(8,4) DEFAULT NULL,
  `turnover_rate` decimal(8,4) DEFAULT NULL,
  `main_inflow_5d` decimal(20,2) DEFAULT NULL,
  `filter_passed` tinyint(1) DEFAULT '1' COMMENT '业绩快速过滤是否通过',
  `filter_reason` varchar(128) DEFAULT NULL COMMENT '被剔除原因',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_date_sector` (`snap_date`, `sector_id`),
  KEY `idx_date_code` (`snap_date`, `stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='强势股流水线-板块龙头候选';

-- ----------------------------
-- 3. 每日最终候选 + 仓位建议
-- ----------------------------
DROP TABLE IF EXISTS `prosperity_pick_daily`;
CREATE TABLE `prosperity_pick_daily` (
  `id` int NOT NULL AUTO_INCREMENT,
  `snap_date` date NOT NULL,
  `stock_code` varchar(20) NOT NULL,
  `stock_name` varchar(50) DEFAULT NULL,
  `sector_name` varchar(64) DEFAULT NULL,
  `finance_score` decimal(8,2) DEFAULT NULL COMMENT 'Step3财务评分',
  `mainline_score` decimal(8,2) DEFAULT NULL COMMENT 'Step4主线评分',
  `combined_score` decimal(8,2) DEFAULT NULL COMMENT '综合评分',
  `net_margin_avg_4q` decimal(8,4) DEFAULT NULL COMMENT '近4季净利率均值',
  `main_biz_ratio` decimal(8,4) DEFAULT NULL COMMENT '主营占比',
  `latest_price` decimal(10,2) DEFAULT NULL,
  `ai_report_json` longtext COMMENT 'AI 10章节深度报告',
  -- 仓位决策字段 (Step5 输出)
  `price_low` decimal(10,2) DEFAULT NULL COMMENT '保守估值股价',
  `price_mid` decimal(10,2) DEFAULT NULL COMMENT '中性估值股价',
  `price_high` decimal(10,2) DEFAULT NULL COMMENT '乐观估值股价',
  `buy_left_price` decimal(10,2) DEFAULT NULL COMMENT '左侧建仓价',
  `buy_right_price` decimal(10,2) DEFAULT NULL COMMENT '右侧确认价',
  `sell_target_1` decimal(10,2) DEFAULT NULL COMMENT '第一目标价',
  `sell_target_2` decimal(10,2) DEFAULT NULL COMMENT '第二目标价',
  `stop_loss_price` decimal(10,2) DEFAULT NULL COMMENT '止损价',
  `core_position_pct` decimal(6,2) DEFAULT NULL COMMENT '核心仓位上限%',
  `tactical_position_pct` decimal(6,2) DEFAULT NULL COMMENT '战术仓位%',
  `action_signal` varchar(20) DEFAULT NULL COMMENT 'add/hold/reduce/observe',
  `degraded` tinyint(1) DEFAULT '0' COMMENT 'AI 是否降级为 mock',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_date_code` (`snap_date`, `stock_code`),
  KEY `idx_date_score` (`snap_date`, `combined_score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='强势股流水线-每日候选清单+仓位建议';
