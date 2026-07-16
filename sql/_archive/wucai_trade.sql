/*
 Navicat Premium Dump SQL

 Source Server         : tencent-windows
 Source Server Type    : MySQL
 Source Server Version : 90700 (9.7.0)
 Source Host           : 43.140.208.165:3306
 Source Schema         : wucai_trade

 Target Server Type    : MySQL
 Target Server Version : 90700 (9.7.0)
 File Encoding         : 65001

 Date: 28/05/2026 17:33:21
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for invest_alert
-- ----------------------------
DROP TABLE IF EXISTS `invest_alert`;
CREATE TABLE `invest_alert` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) NOT NULL,
  `signal_type` varchar(30) NOT NULL COMMENT 'undervalued/overvalued/break_ma13/reversal_buy/peak_warning',
  `level` tinyint DEFAULT '2' COMMENT '1低 2中 3高',
  `title` varchar(200) DEFAULT NULL,
  `content` text,
  `trigger_price` decimal(10,2) DEFAULT NULL,
  `trigger_at` datetime DEFAULT NULL,
  `channels` varchar(100) DEFAULT NULL COMMENT 'email,serverjiang,inapp',
  `pushed` tinyint DEFAULT '0',
  `read_flag` tinyint DEFAULT '0',
  `user_id` int DEFAULT NULL COMMENT '关联用户ID，NULL=广播',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_alert_code_time` (`stock_code`,`trigger_at`),
  KEY `idx_alert_signal` (`signal_type`),
  KEY `idx_alert_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提醒记录（站内信+推送日志）';

-- ----------------------------
-- Table structure for invest_industry_prosperity
-- ----------------------------
DROP TABLE IF EXISTS `invest_industry_prosperity`;
CREATE TABLE `invest_industry_prosperity` (
  `id` int NOT NULL AUTO_INCREMENT,
  `snapshot_date` date NOT NULL,
  `industry` varchar(64) NOT NULL,
  `company_cnt` int DEFAULT NULL,
  `rev_yoy_avg` decimal(8,4) DEFAULT NULL,
  `hi_ratio` decimal(5,2) DEFAULT NULL COMMENT '高增速公司占比',
  `industry_score` decimal(5,2) DEFAULT NULL,
  `level` varchar(20) DEFAULT NULL COMMENT 'super_hot/hot/normal/cold',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ind_date` (`snapshot_date`,`industry`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='行业景气度日快照';

-- ----------------------------
-- Table structure for invest_pool_import_log
-- ----------------------------
DROP TABLE IF EXISTS `invest_pool_import_log`;
CREATE TABLE `invest_pool_import_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `image_path` varchar(500) NOT NULL,
  `pool_type` varchar(20) DEFAULT NULL COMMENT 'quality/tech_vc',
  `source_title` varchar(200) DEFAULT NULL,
  `raw_ocr_json` mediumtext,
  `parsed_json` mediumtext,
  `rows_total` int DEFAULT '0',
  `rows_imported` int DEFAULT '0',
  `rows_failed` int DEFAULT '0',
  `failed_reason` text,
  `operator` varchar(50) DEFAULT NULL,
  `parse_status` varchar(20) DEFAULT 'pending' COMMENT 'pending/done/failed',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='图片导入日志';

-- ----------------------------
-- Table structure for invest_prosperity_pick
-- ----------------------------
DROP TABLE IF EXISTS `invest_prosperity_pick`;
CREATE TABLE `invest_prosperity_pick` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) NOT NULL,
  `stock_name` varchar(50) NOT NULL,
  `analysis_date` date NOT NULL,
  `result_json` mediumtext,
  `image_url` varchar(512) DEFAULT NULL,
  `image_prompt` text,
  `degraded` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_date` (`stock_code`,`analysis_date`),
  KEY `idx_analysis_date` (`analysis_date`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='景气度选股 AI 研究结果';

-- ----------------------------
-- Table structure for invest_revenue_history
-- ----------------------------
DROP TABLE IF EXISTS `invest_revenue_history`;
CREATE TABLE `invest_revenue_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) NOT NULL,
  `year` int NOT NULL,
  `is_forecast` tinyint DEFAULT '0' COMMENT '0=实际 1=预测',
  `revenue_yi` decimal(12,2) DEFAULT NULL COMMENT '营收（亿）',
  `source` varchar(30) DEFAULT 'image_import',
  `import_log_id` bigint DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_year` (`stock_code`,`year`),
  KEY `idx_rev_code` (`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='历史营收存档（来自图片导入）';

-- ----------------------------
-- Table structure for invest_stock_pool
-- ----------------------------
DROP TABLE IF EXISTS `invest_stock_pool`;
CREATE TABLE `invest_stock_pool` (
  `id` int NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) NOT NULL COMMENT '股票代码',
  `stock_name` varchar(255) DEFAULT NULL,
  `pool_type` enum('quality','tech_vc','tech_ai','potential','innovative_drug') NOT NULL COMMENT '质量优选/科技风投/科技AI/潜力监控/创新药',
  `undervalued_price` decimal(10,2) DEFAULT NULL COMMENT '低估价',
  `fair_price` decimal(10,2) DEFAULT NULL COMMENT '合理价',
  `overvalued_price` decimal(10,2) DEFAULT NULL COMMENT '高估价',
  `target_buy_price` decimal(10,2) DEFAULT NULL COMMENT '希望买入价',
  `target_sell_price` decimal(10,2) DEFAULT NULL COMMENT '希望卖出价',
  `revenue_forecast_y0` decimal(10,2) DEFAULT NULL COMMENT '今年预测营收(亿)',
  `revenue_forecast_y1` decimal(10,2) DEFAULT NULL COMMENT '明年预测营收(亿)',
  `revenue_forecast_y2` decimal(10,2) DEFAULT NULL COMMENT '后年预测营收(亿)',
  `q1_gross_margin` decimal(5,2) DEFAULT NULL COMMENT '2026 Q1 毛利率(%)',
  `q1_net_margin` decimal(5,2) DEFAULT NULL COMMENT '2026 Q1 净利率(%)',
  `q1_revenue_growth` decimal(6,2) DEFAULT NULL COMMENT '2026 Q1 营收增速(%)',
  `q1_netprofit_growth` decimal(6,2) DEFAULT NULL COMMENT '2026 Q1 净利润增速(%)',
  `min_ps_5y` decimal(5,2) DEFAULT NULL COMMENT '近5年最低动态PS(倍)',
  `target_market_cap` decimal(12,2) DEFAULT NULL COMMENT '目标市值（亿元）',
  `profit_level` varchar(20) DEFAULT NULL COMMENT '盈亏/景气等级',
  `valuation_range` varchar(20) DEFAULT NULL COMMENT '估值区间',
  `current_market_cap` decimal(10,2) DEFAULT NULL COMMENT '当前市值(亿)',
  `ytd_return` decimal(6,2) DEFAULT NULL COMMENT '今年涨幅(%)',
  `rev_2023` decimal(10,2) DEFAULT NULL COMMENT '2023年营收(亿)',
  `rev_2024` decimal(10,2) DEFAULT NULL COMMENT '2024年营收(亿)',
  `rev_2025` decimal(10,2) DEFAULT NULL COMMENT '2025年营收(亿)',
  `memo` text COMMENT '投资逻辑备注',
  `target_price` decimal(10,2) DEFAULT NULL COMMENT '目标价（可选）',
  `revenue_2023` decimal(10,2) DEFAULT NULL COMMENT '2023年营收（亿元）',
  `revenue_2024` decimal(10,2) DEFAULT NULL COMMENT '2024年营收（亿元）',
  `revenue_2025` decimal(10,2) DEFAULT NULL COMMENT '2025年营收（亿元）',
  `status` enum('watching','holding','exited') DEFAULT 'watching' COMMENT '观察/持仓/已离场',
  `alert_state` varchar(20) DEFAULT 'none' COMMENT '提醒状态',
  `last_alert_at` datetime DEFAULT NULL COMMENT '上次提醒时间',
  `alert_minute_1m_pct` decimal(8,2) DEFAULT NULL COMMENT '科技AI 1分钟涨跌幅告警阈值(%)',
  `alert_minute_5m_pct` decimal(8,2) DEFAULT NULL COMMENT '科技AI 5分钟涨跌幅告警阈值(%)',
  `alert_daily_pct` decimal(8,2) DEFAULT NULL COMMENT '科技AI 当日涨跌幅告警阈值(%)',
  `alert_three_day_pct` decimal(8,2) DEFAULT NULL COMMENT '科技AI 3日涨跌幅告警阈值(%)',
  `alert_turnover_ratio_pct` decimal(8,2) DEFAULT NULL COMMENT '科技AI 换手率放大告警阈值，占5日均值比例(%)',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_pool_code` (`stock_code`)
) ENGINE=InnoDB AUTO_INCREMENT=25 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='龙江投资股票池';

-- ----------------------------
-- Table structure for invest_stock_prosperity
-- ----------------------------
DROP TABLE IF EXISTS `invest_stock_prosperity`;
CREATE TABLE `invest_stock_prosperity` (
  `id` int NOT NULL AUTO_INCREMENT,
  `snapshot_date` date NOT NULL,
  `stock_code` varchar(20) NOT NULL,
  `prosperity_score` decimal(5,2) DEFAULT NULL,
  `trend_label` varchar(20) DEFAULT NULL COMMENT 'up_trend/peak/reversal/down',
  `rev_yoy_4q` decimal(8,4) DEFAULT NULL,
  `np_yoy_4q` decimal(8,4) DEFAULT NULL,
  `industry_score` decimal(5,2) DEFAULT NULL,
  `final_score` decimal(5,2) DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stk_date` (`snapshot_date`,`stock_code`),
  KEY `idx_stk_pros_score` (`snapshot_date`,`final_score`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公司景气度日快照';

-- ----------------------------
-- Table structure for invest_strategy_param
-- ----------------------------
DROP TABLE IF EXISTS `invest_strategy_param`;
CREATE TABLE `invest_strategy_param` (
  `id` int NOT NULL AUTO_INCREMENT,
  `param_key` varchar(50) NOT NULL,
  `param_value` varchar(200) DEFAULT NULL,
  `remark` varchar(200) DEFAULT NULL,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_param_key` (`param_key`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='策略参数配置';

-- ----------------------------
-- Table structure for invest_user
-- ----------------------------
DROP TABLE IF EXISTS `invest_user`;
CREATE TABLE `invest_user` (
  `id` int NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL,
  `password` varchar(100) NOT NULL,
  `email` varchar(120) DEFAULT NULL,
  `server_jiang_key` varchar(200) DEFAULT NULL COMMENT 'Server酱 SendKey',
  `role` varchar(20) DEFAULT 'user' COMMENT 'admin/user',
  `push_pref` varchar(50) DEFAULT 'serverjiang' COMMENT 'serverjiang/email/both',
  `enabled` tinyint DEFAULT '1',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';

-- ----------------------------
-- Table structure for invest_watchlist
-- ----------------------------
DROP TABLE IF EXISTS `invest_watchlist`;
CREATE TABLE `invest_watchlist` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL,
  `stock_code` varchar(20) NOT NULL,
  `buy_price` decimal(10,2) DEFAULT NULL,
  `sell_price` decimal(10,2) DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_stock` (`user_id`,`stock_code`),
  KEY `idx_wl_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户自选股';

-- ----------------------------
-- Table structure for study_card
-- ----------------------------
DROP TABLE IF EXISTS `study_card`;
CREATE TABLE `study_card` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `node_id` bigint NOT NULL,
  `card_type` varchar(20) NOT NULL COMMENT 'ai_detail / flash',
  `stage` varchar(50) DEFAULT NULL COMMENT '阶段一/阶段二 等',
  `title` varchar(200) DEFAULT NULL,
  `body` text,
  `image_url` varchar(500) DEFAULT NULL,
  `sort` int DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_card_node` (`node_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2012 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识卡片';

-- ----------------------------
-- Table structure for study_category
-- ----------------------------
DROP TABLE IF EXISTS `study_category`;
CREATE TABLE `study_category` (
  `id` int NOT NULL AUTO_INCREMENT,
  `name` varchar(32) NOT NULL,
  `sort` int DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_study_cat_name` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公开项目分类';

-- ----------------------------
-- Table structure for study_course
-- ----------------------------
DROP TABLE IF EXISTS `study_course`;
CREATE TABLE `study_course` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(200) NOT NULL COMMENT '课程/项目标题',
  `summary` text COMMENT '课程简介',
  `cover_text` varchar(200) DEFAULT NULL COMMENT '封面文字 / emoji',
  `cover_color` varchar(20) DEFAULT '#e8f5e9',
  `owner` varchar(64) DEFAULT NULL,
  `source_type` varchar(20) DEFAULT 'upload' COMMENT 'upload/cloud/url',
  `visibility` varchar(10) DEFAULT 'private' COMMENT 'private/public',
  `status` varchar(20) DEFAULT 'ready' COMMENT 'processing/ready',
  `progress` int DEFAULT '100' COMMENT '解析进度 0-100',
  `category_id` int DEFAULT NULL,
  `learn_status` varchar(20) DEFAULT 'pending' COMMENT 'pending/learning/done',
  `mastered_cnt` int DEFAULT '0',
  `total_cnt` int DEFAULT '0',
  `learner_cnt` int DEFAULT '0' COMMENT '已学习人数',
  `book_cover_url` varchar(500) DEFAULT NULL,
  `recommend_images` text COMMENT '推荐学习配图 JSON 数组',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_course_visibility` (`visibility`),
  KEY `idx_course_category` (`category_id`)
) ENGINE=InnoDB AUTO_INCREMENT=106 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='学习项目/课程';

-- ----------------------------
-- Table structure for study_knowledge_node
-- ----------------------------
DROP TABLE IF EXISTS `study_knowledge_node`;
CREATE TABLE `study_knowledge_node` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `course_id` bigint NOT NULL,
  `parent_id` bigint DEFAULT NULL,
  `title` varchar(200) NOT NULL,
  `summary` text,
  `definition` text COMMENT '定义/核心思想',
  `sort` int DEFAULT '0',
  `level` int DEFAULT '1',
  `mastered` tinyint(1) DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_node_course` (`course_id`),
  KEY `idx_node_parent` (`parent_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1051 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识点树';

-- ----------------------------
-- Table structure for study_material
-- ----------------------------
DROP TABLE IF EXISTS `study_material`;
CREATE TABLE `study_material` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `course_id` bigint NOT NULL,
  `file_name` varchar(255) NOT NULL,
  `file_type` varchar(20) DEFAULT NULL,
  `file_path` varchar(500) DEFAULT NULL,
  `size` bigint DEFAULT '0',
  `parse_status` varchar(20) DEFAULT 'pending' COMMENT 'pending/parsing/done/failed',
  `progress` int DEFAULT '0',
  `extracted_text` mediumtext COMMENT '抽取出的文本(摘要)',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mat_course` (`course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='上传学习资料';

-- ----------------------------
-- Table structure for study_quiz
-- ----------------------------
DROP TABLE IF EXISTS `study_quiz`;
CREATE TABLE `study_quiz` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `node_id` bigint NOT NULL,
  `stem` text NOT NULL COMMENT '题干',
  `options_json` text NOT NULL COMMENT '选项 JSON [{"key":"A","text":"..."}]',
  `answer` varchar(8) NOT NULL COMMENT '正确选项 key',
  `analysis` text COMMENT '解析',
  `sort` int DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_quiz_node` (`node_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3002 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='测验题目';

-- ----------------------------
-- Table structure for study_quiz_record
-- ----------------------------
DROP TABLE IF EXISTS `study_quiz_record`;
CREATE TABLE `study_quiz_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `quiz_id` bigint NOT NULL,
  `picked` varchar(8) DEFAULT NULL,
  `correct` tinyint(1) DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_qr_quiz` (`quiz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='答题记录';

-- ----------------------------
-- Table structure for tech_ai_quote_snapshot
-- ----------------------------
DROP TABLE IF EXISTS `tech_ai_quote_snapshot`;
CREATE TABLE `tech_ai_quote_snapshot` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) NOT NULL COMMENT '项目内部代码，如 300733.sz',
  `quote_time` datetime NOT NULL COMMENT '行情时间',
  `latest_price` decimal(10,2) DEFAULT NULL COMMENT '最新价',
  `prev_close_price` decimal(10,2) DEFAULT NULL COMMENT '昨收',
  `open_price` decimal(10,2) DEFAULT NULL COMMENT '今日开盘价',
  `volume` bigint DEFAULT NULL COMMENT '成交量',
  `amount` decimal(20,2) DEFAULT NULL COMMENT '成交额',
  `turnover_rate` decimal(10,4) DEFAULT NULL COMMENT '换手率(%)',
  `minute1_open_price` decimal(10,2) DEFAULT NULL COMMENT '1分钟窗口开盘价',
  `minute1_time` datetime DEFAULT NULL COMMENT '1分钟K线时间',
  `minute5_open_price` decimal(10,2) DEFAULT NULL COMMENT '5分钟窗口开盘价',
  `minute5_time` datetime DEFAULT NULL COMMENT '5分钟K线时间',
  `source` varchar(20) DEFAULT 'qmt',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_tech_ai_quote_code_time` (`stock_code`,`quote_time`),
  KEY `idx_tech_ai_quote_time` (`quote_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='科技AI QMT实时行情快照';

-- ----------------------------
-- Table structure for trade_calendar_event
-- ----------------------------
DROP TABLE IF EXISTS `trade_calendar_event`;
CREATE TABLE `trade_calendar_event` (
  `id` int NOT NULL AUTO_INCREMENT,
  `event_date` date NOT NULL COMMENT '事件日期',
  `event_time` varchar(10) DEFAULT NULL COMMENT '事件时间(HH:MM)',
  `country` varchar(10) NOT NULL DEFAULT 'CN' COMMENT 'CN/US/EU/JP',
  `category` varchar(30) NOT NULL COMMENT 'rate/inflation/employment/gdp/pmi/trade/policy/other',
  `title` varchar(200) NOT NULL,
  `importance` tinyint DEFAULT '2' COMMENT '1=低 2=中 3=高',
  `previous_value` varchar(50) DEFAULT NULL COMMENT '前值',
  `forecast_value` varchar(50) DEFAULT NULL COMMENT '预测值',
  `actual_value` varchar(50) DEFAULT NULL COMMENT '实际值',
  `impact` varchar(200) DEFAULT NULL COMMENT '市场影响说明',
  `ai_prompt` text COMMENT 'AI提问prompt',
  `source` varchar(50) DEFAULT NULL COMMENT 'eastmoney/fred/manual',
  `source_url` varchar(500) DEFAULT NULL,
  `is_recurring` tinyint DEFAULT '0',
  `recurrence_rule` varchar(100) DEFAULT NULL COMMENT '周期规则，如"每月第一个周五"',
  `status` varchar(20) DEFAULT 'upcoming' COMMENT 'upcoming/released/cancelled',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_calendar_date_title` (`event_date`,`title`),
  KEY `idx_calendar_date` (`event_date`),
  KEY `idx_calendar_country` (`country`),
  KEY `idx_calendar_category` (`category`),
  KEY `idx_calendar_importance` (`importance`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='财经日历事件';

-- ----------------------------
-- Table structure for trade_macro_indicator
-- ----------------------------
DROP TABLE IF EXISTS `trade_macro_indicator`;
CREATE TABLE `trade_macro_indicator` (
  `id` int NOT NULL AUTO_INCREMENT,
  `indicator_date` date NOT NULL COMMENT '指标月份(月末日期)',
  `cpi_yoy` decimal(10,2) DEFAULT NULL COMMENT 'CPI同比(%)',
  `ppi_yoy` decimal(10,2) DEFAULT NULL COMMENT 'PPI同比(%)',
  `pmi` decimal(10,2) DEFAULT NULL COMMENT 'PMI',
  `m2_yoy` decimal(10,2) DEFAULT NULL COMMENT 'M2同比增速(%)',
  `shrzgm` decimal(14,0) DEFAULT NULL COMMENT '社融规模增量(亿元)',
  `lpr_1y` decimal(6,2) DEFAULT NULL COMMENT 'LPR 1年期(%)',
  `lpr_5y` decimal(6,2) DEFAULT NULL COMMENT 'LPR 5年期(%)',
  `data_source` varchar(20) DEFAULT 'akshare',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_macro_date` (`indicator_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='月度宏观指标';

-- ----------------------------
-- Table structure for trade_rate_daily
-- ----------------------------
DROP TABLE IF EXISTS `trade_rate_daily`;
CREATE TABLE `trade_rate_daily` (
  `id` int NOT NULL AUTO_INCREMENT,
  `rate_date` date NOT NULL COMMENT '日期',
  `cn_bond_10y` decimal(8,4) DEFAULT NULL COMMENT '中国10年期国债收益率(%)',
  `us_bond_10y` decimal(8,4) DEFAULT NULL COMMENT '美国10年期国债收益率(%)',
  `data_source` varchar(20) DEFAULT 'akshare',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_rate_date` (`rate_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='日频利率指标';

-- ----------------------------
-- Table structure for trade_report_consensus
-- ----------------------------
DROP TABLE IF EXISTS `trade_report_consensus`;
CREATE TABLE `trade_report_consensus` (
  `id` int NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) NOT NULL,
  `broker` varchar(50) DEFAULT NULL COMMENT '券商',
  `report_date` date DEFAULT NULL,
  `rating` varchar(20) DEFAULT NULL COMMENT '买入/增持/中性/减持',
  `target_price` decimal(10,2) DEFAULT NULL,
  `eps_forecast_current` decimal(10,4) DEFAULT NULL COMMENT '当年EPS预测',
  `eps_forecast_next` decimal(10,4) DEFAULT NULL COMMENT '次年EPS预测',
  `revenue_forecast` decimal(20,2) DEFAULT NULL COMMENT '营收预测(亿)',
  `source_file` varchar(500) DEFAULT NULL COMMENT 'PDF文件路径',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_consensus_unique` (`stock_code`,`broker`,`report_date`),
  KEY `idx_consensus_code` (`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='研报一致性预期';

-- ----------------------------
-- Table structure for trade_stock_basic
-- ----------------------------
DROP TABLE IF EXISTS `trade_stock_basic`;
CREATE TABLE `trade_stock_basic` (
  `stock_code` varchar(20) NOT NULL COMMENT '股票代码，如 600519.SH',
  `stock_name` varchar(50) DEFAULT NULL COMMENT '证券名称',
  `exchange` varchar(10) DEFAULT NULL COMMENT '交易所 SH/SZ',
  `list_date` date DEFAULT NULL COMMENT '上市日期',
  `price_tick` decimal(10,4) DEFAULT NULL COMMENT '最小价格变动单位',
  `total_shares` bigint DEFAULT NULL COMMENT '总股本(股)',
  `float_shares` bigint DEFAULT NULL COMMENT '流通股本(股)',
  `free_float_shares` bigint DEFAULT NULL COMMENT '自由流通股本(股)',
  `instrument_status` int DEFAULT NULL COMMENT '合约状态',
  `is_trading` tinyint DEFAULT NULL COMMENT '是否交易中 0/1',
  `sector_names` text COMMENT '所属板块，逗号分隔',
  `capital_report_date` date DEFAULT NULL COMMENT '股本数据报告期',
  `total_capital` decimal(20,4) DEFAULT NULL COMMENT '总股本(Capital表)',
  `circulating_capital` decimal(20,4) DEFAULT NULL COMMENT '流通股本(Capital表)',
  `free_float_capital` decimal(20,4) DEFAULT NULL COMMENT '自由流通股本',
  `restrict_circulating_capital` decimal(20,4) DEFAULT NULL COMMENT '限售流通股本',
  `last_div_date` date DEFAULT NULL COMMENT '最近除权除息日',
  `last_div_cash` decimal(10,4) DEFAULT NULL COMMENT '每股派息(元)',
  `last_div_stock_bonus` decimal(10,4) DEFAULT NULL COMMENT '每股送股',
  `last_div_stock_gift` decimal(10,4) DEFAULT NULL COMMENT '每股转增',
  `last_div_dr` decimal(12,6) DEFAULT NULL COMMENT '除权除息因子',
  `data_source` varchar(20) DEFAULT 'qmt',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `pe_ttm` decimal(10,2) DEFAULT NULL COMMENT 'PE(TTM)',
  `pb` decimal(10,2) DEFAULT NULL COMMENT 'PB(市净率)',
  `ps_ttm` decimal(10,2) DEFAULT NULL COMMENT 'PS(TTM)',
  `valuation_level` varchar(10) DEFAULT NULL COMMENT '估值水平',
  `valuation_updated_at` datetime DEFAULT NULL COMMENT '估值更新时间',
  PRIMARY KEY (`stock_code`),
  KEY `idx_basic_name` (`stock_name`),
  KEY `idx_basic_exchange` (`exchange`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='股票基础信息';

-- ----------------------------
-- Table structure for trade_stock_daily
-- ----------------------------
DROP TABLE IF EXISTS `trade_stock_daily`;
CREATE TABLE `trade_stock_daily` (
  `id` int NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) NOT NULL COMMENT '股票代码',
  `trade_date` date NOT NULL COMMENT '交易日期',
  `open_price` decimal(10,2) DEFAULT NULL COMMENT '开盘价',
  `high_price` decimal(10,2) DEFAULT NULL COMMENT '最高价',
  `low_price` decimal(10,2) DEFAULT NULL COMMENT '最低价',
  `close_price` decimal(10,2) DEFAULT NULL COMMENT '收盘价(前复权)',
  `volume` bigint DEFAULT NULL COMMENT '成交量(股)',
  `amount` decimal(20,2) DEFAULT NULL COMMENT '成交额(元)',
  `turnover_rate` decimal(10,4) DEFAULT NULL COMMENT '换手率',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_stock_daily_code_date` (`stock_code`,`trade_date`),
  KEY `idx_stock_daily_code` (`stock_code`),
  KEY `idx_stock_daily_date` (`trade_date`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='日K线数据';

-- ----------------------------
-- Table structure for trade_stock_financial
-- ----------------------------
DROP TABLE IF EXISTS `trade_stock_financial`;
CREATE TABLE `trade_stock_financial` (
  `id` int NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) NOT NULL,
  `stock_name` varchar(50) DEFAULT NULL,
  `report_date` date NOT NULL COMMENT '报告期，如 2024-12-31',
  `revenue` decimal(20,2) DEFAULT NULL COMMENT '营业收入(元)',
  `revenue_yoy` decimal(10,4) DEFAULT NULL,
  `net_profit` decimal(20,2) DEFAULT NULL COMMENT '净利润(元)',
  `deducted_netprofit_yoy` decimal(10,4) DEFAULT NULL,
  `deducted_netprofit_ttm` decimal(20,2) DEFAULT NULL,
  `eps` decimal(10,4) DEFAULT NULL COMMENT '每股收益',
  `roe` decimal(10,4) DEFAULT NULL COMMENT 'ROE(%)',
  `roa` decimal(10,4) DEFAULT NULL COMMENT 'ROA(%)',
  `gross_margin` decimal(10,4) DEFAULT NULL COMMENT '毛利率(%)',
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
) ENGINE=InnoDB AUTO_INCREMENT=176519 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='季度财务数据';

-- ----------------------------
-- Table structure for trade_stock_info
-- ----------------------------
DROP TABLE IF EXISTS `trade_stock_info`;
CREATE TABLE `trade_stock_info` (
  `id` int NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) NOT NULL COMMENT '股票代码',
  `stock_name` varchar(64) NOT NULL COMMENT '股票名称',
  `exchange` varchar(16) DEFAULT NULL COMMENT '交易所',
  `industry` varchar(64) DEFAULT NULL COMMENT '所属行业',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_stock_code` (`stock_code`),
  KEY `idx_stock_name` (`stock_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='股票基础信息';

-- ----------------------------
-- Table structure for trade_stock_news
-- ----------------------------
DROP TABLE IF EXISTS `trade_stock_news`;
CREATE TABLE `trade_stock_news` (
  `id` int NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) DEFAULT NULL COMMENT '股票代码',
  `sector_code` varchar(20) DEFAULT NULL COMMENT '板块代码',
  `news_type` varchar(20) NOT NULL COMMENT 'announcement/news/report',
  `title` varchar(500) NOT NULL,
  `content` text,
  `summary` text,
  `source` varchar(50) DEFAULT NULL COMMENT 'eastmoney/cailianshe/kimi',
  `source_url` varchar(500) DEFAULT NULL,
  `published_at` datetime DEFAULT NULL,
  `sentiment` varchar(20) DEFAULT NULL COMMENT 'positive/negative/neutral',
  `sentiment_score` decimal(5,2) DEFAULT NULL COMMENT '-1到1',
  `is_important` tinyint DEFAULT '0',
  `is_read` tinyint DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_stock_news_code` (`stock_code`),
  KEY `idx_stock_news_published` (`published_at`),
  KEY `idx_stock_news_type` (`news_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='新闻事件';

-- ----------------------------
-- Table structure for trade_stock_realtime_kline
-- ----------------------------
DROP TABLE IF EXISTS `trade_stock_realtime_kline`;
CREATE TABLE `trade_stock_realtime_kline` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) NOT NULL COMMENT '股票代码，小写后缀格式，如 600519.sh',
  `period` varchar(10) NOT NULL COMMENT '周期：1m/5m/1h/1d',
  `kline_time` datetime NOT NULL COMMENT 'K线时间',
  `open_price` decimal(12,4) DEFAULT NULL COMMENT '开盘价',
  `high_price` decimal(12,4) DEFAULT NULL COMMENT '最高价',
  `low_price` decimal(12,4) DEFAULT NULL COMMENT '最低价',
  `close_price` decimal(12,4) DEFAULT NULL COMMENT '收盘价/最新价',
  `volume` bigint DEFAULT NULL COMMENT '成交量',
  `amount` decimal(20,2) DEFAULT NULL COMMENT '成交额',
  `pre_close` decimal(12,4) DEFAULT NULL COMMENT '昨收/前收',
  `turnover_rate` decimal(10,4) DEFAULT NULL COMMENT '换手率(%)',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_realtime_kline_unique` (`stock_code`,`period`,`kline_time`),
  KEY `idx_realtime_kline_code_period` (`stock_code`,`period`),
  KEY `idx_realtime_kline_time` (`kline_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='股票分钟/小时/日K线';

-- ----------------------------
-- Table structure for trade_stock_realtime_quote
-- ----------------------------
DROP TABLE IF EXISTS `trade_stock_realtime_quote`;
CREATE TABLE `trade_stock_realtime_quote` (
  `stock_code` varchar(20) NOT NULL COMMENT '股票代码，小写后缀格式，如 600519.sh',
  `quote_time` datetime DEFAULT NULL COMMENT '行情时间',
  `latest_price` decimal(12,4) DEFAULT NULL COMMENT '最新价',
  `last_close` decimal(12,4) DEFAULT NULL COMMENT '昨收',
  `open_price` decimal(12,4) DEFAULT NULL COMMENT '开盘价',
  `volume` bigint DEFAULT NULL COMMENT '成交量',
  `amount` decimal(20,2) DEFAULT NULL COMMENT '成交额',
  `turnover_rate` decimal(10,4) DEFAULT NULL COMMENT '换手率(%)',
  `kline_time_1m` datetime DEFAULT NULL COMMENT '最新1m K线时间',
  `kline_time_5m` datetime DEFAULT NULL COMMENT '最新5m K线时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`stock_code`),
  KEY `idx_realtime_quote_time` (`quote_time`),
  KEY `idx_realtime_kline_1m` (`kline_time_1m`),
  KEY `idx_realtime_kline_5m` (`kline_time_5m`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='股票实时行情快照';

SET FOREIGN_KEY_CHECKS = 1;
