-- market_recap_multi_day_evaluation.sql
-- 新增多日强弱评估字段（invest_market_recap 表）

ALTER TABLE invest_market_recap
  ADD COLUMN multi_day_evaluation TEXT COMMENT '多日强弱评估 JSON' AFTER next_day_strategy;
