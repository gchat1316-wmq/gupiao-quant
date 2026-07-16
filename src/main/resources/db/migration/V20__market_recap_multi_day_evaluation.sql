-- V20: invest_market_recap 多日强弱评估字段
-- Source: sql/market_recap_multi_day_evaluation.sql

ALTER TABLE invest_market_recap
  ADD COLUMN multi_day_evaluation TEXT COMMENT '多日强弱评估 JSON' AFTER next_day_strategy;
