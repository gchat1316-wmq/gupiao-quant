ALTER TABLE `invest_stock_pool`
  ADD COLUMN `alert_minute_1m_pct` decimal(8,2) DEFAULT NULL COMMENT '科技AI 1分钟涨跌幅告警阈值(%)' AFTER `last_alert_at`,
  ADD COLUMN `alert_minute_5m_pct` decimal(8,2) DEFAULT NULL COMMENT '科技AI 5分钟涨跌幅告警阈值(%)' AFTER `alert_minute_1m_pct`,
  ADD COLUMN `alert_daily_pct` decimal(8,2) DEFAULT NULL COMMENT '科技AI 当日涨跌幅告警阈值(%)' AFTER `alert_minute_5m_pct`,
  ADD COLUMN `alert_three_day_pct` decimal(8,2) DEFAULT NULL COMMENT '科技AI 3日涨跌幅告警阈值(%)' AFTER `alert_daily_pct`,
  ADD COLUMN `alert_turnover_ratio_pct` decimal(8,2) DEFAULT NULL COMMENT '科技AI 换手率放大告警阈值，占5日均值比例(%)' AFTER `alert_three_day_pct`;
