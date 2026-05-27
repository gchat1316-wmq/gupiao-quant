-- 景气度选股 · AI 全维度个股研究结果缓存表
CREATE TABLE IF NOT EXISTS invest_prosperity_pick (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  stock_code      VARCHAR(20)  NOT NULL,
  stock_name      VARCHAR(50)  NOT NULL,
  analysis_date   DATE         NOT NULL,
  result_json     MEDIUMTEXT,
  image_url       VARCHAR(512),
  image_prompt    TEXT,
  degraded        TINYINT(1) NOT NULL DEFAULT 0,
  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_code_date (stock_code, analysis_date),
  KEY idx_analysis_date (analysis_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='景气度选股 AI 研究结果';
