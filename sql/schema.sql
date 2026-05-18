-- 数据库初始化脚本
CREATE DATABASE IF NOT EXISTS `gupiao_quant`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `gupiao_quant`;

-- 季度财务数据（用户已存在的表）
CREATE TABLE IF NOT EXISTS `trade_stock_financial` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) NOT NULL,
  `report_date` date NOT NULL COMMENT '报告期，如 2024-12-31',
  `revenue` decimal(20,2) DEFAULT NULL COMMENT '营业收入(元)',
  `net_profit` decimal(20,2) DEFAULT NULL COMMENT '净利润(元)',
  `eps` decimal(10,4) DEFAULT NULL COMMENT '每股收益',
  `roe` decimal(10,4) DEFAULT NULL COMMENT 'ROE(%)',
  `roa` decimal(10,4) DEFAULT NULL COMMENT 'ROA(%)',
  `gross_margin` decimal(10,4) DEFAULT NULL COMMENT '毛利率(%)',
  `revenue_yoy` decimal(10,4) DEFAULT NULL COMMENT '营收同比(%)',
  `deducted_netprofit_yoy` decimal(10,4) DEFAULT NULL COMMENT '扣非净利润同比(%)',
  `deducted_netprofit_ttm` decimal(20,2) DEFAULT NULL COMMENT '扣非净利润TTM(元)',
  `net_margin` decimal(10,4) DEFAULT NULL COMMENT '净利率(%)',
  `debt_ratio` decimal(10,4) DEFAULT NULL COMMENT '资产负债率(%)',
  `current_ratio` decimal(10,4) DEFAULT NULL COMMENT '流动比率',
  `operating_cashflow` decimal(20,2) DEFAULT NULL COMMENT '经营现金流(元)',
  `total_assets` decimal(20,2) DEFAULT NULL COMMENT '总资产(元)',
  `total_equity` decimal(20,2) DEFAULT NULL COMMENT '净资产(元)',
  `data_source` varchar(20) DEFAULT 'akshare',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_fina_code_date` (`stock_code`,`report_date`),
  KEY `idx_fina_code` (`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='季度财务数据';

-- 股票基础信息表（用于名称↔代码映射、模糊检索）
CREATE TABLE IF NOT EXISTS `trade_stock_info` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) NOT NULL COMMENT '股票代码',
  `stock_name` varchar(64) NOT NULL COMMENT '股票名称',
  `exchange` varchar(16) DEFAULT NULL COMMENT '交易所',
  `industry` varchar(64) DEFAULT NULL COMMENT '所属行业',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_stock_code` (`stock_code`),
  KEY `idx_stock_name` (`stock_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='股票基础信息';

-- 龙江投资：股票池
CREATE TABLE IF NOT EXISTS `invest_stock_pool` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) NOT NULL COMMENT '股票代码',
  `pool_type` enum('quality','tech_vc') NOT NULL COMMENT '质量优选/科技风投',
  `memo` text COMMENT '投资逻辑备注',
  `target_price` decimal(10,2) DEFAULT NULL COMMENT '目标价（可选）',
  `status` enum('watching','holding','exited') DEFAULT 'watching' COMMENT '观察/持仓/已离场',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_pool_code` (`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='龙江投资股票池';
