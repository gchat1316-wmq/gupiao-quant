-- V19: 实战选股 · 分析记录表（含历史、分享、PDF）
-- Source: sql/practical_select_init.sql (entity InvestPracticalSelectRecord)

CREATE TABLE IF NOT EXISTS `invest_practical_select_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) NOT NULL COMMENT '股票代码（带后缀，如 603283.SH）',
  `stock_name` varchar(100) DEFAULT NULL COMMENT '股票名称',
  `keyword` varchar(100) DEFAULT NULL COMMENT '用户原始输入',
  `status` varchar(20) NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS / FAILED',
  `headline` varchar(500) DEFAULT NULL COMMENT '综合结论一句话（用于列表展示）',
  `verdict` varchar(20) DEFAULT NULL COMMENT '估值结论：低估 / 合理 / 高估',
  `result_json` longtext COMMENT '完整分析结果 JSON（PracticalSelectResponse 序列化）',
  `pdf_path` varchar(500) DEFAULT NULL COMMENT '生成的 PDF 路径（相对 upload-dir）',
  `share_token` varchar(64) DEFAULT NULL COMMENT '分享 token（UUID）',
  `is_public` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否启用公开分享',
  `elapsed_ms` bigint DEFAULT NULL COMMENT '分析耗时（毫秒）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_stock_code` (`stock_code`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_share_token` (`share_token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实战选股分析记录';
