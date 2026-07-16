-- V8: prosperity ALTERs — folds v2/v3/v4/v5 deltas
-- Source: SchemaInitializer
--   - ensureProsperityHotSectorAStockColumns()  → ALTER prosperity_hot_sector
--   - ensureProsperityLeaderMainlineReason()    → ALTER prosperity_leader_candidate
--   - ensureProsperityLeaderFinanceColumns()    → ALTER prosperity_leader_candidate
--   - ensurePickDailyMemoColumn()               → ALTER prosperity_pick_daily
--   - ensurePickDailyRevenueYoyMin3q()          → ALTER prosperity_pick_daily
--   - ensureProsperityPickNewColumns()          → ALTER invest_prosperity_pick

-- prosperity_hot_sector (板块上涨/下跌/领涨股)
ALTER TABLE prosperity_hot_sector ADD COLUMN up_count            INT            DEFAULT NULL COMMENT '板块上涨家数';
ALTER TABLE prosperity_hot_sector ADD COLUMN down_count          INT            DEFAULT NULL COMMENT '板块下跌家数';
ALTER TABLE prosperity_hot_sector ADD COLUMN lead_stock          VARCHAR(64)    DEFAULT NULL COMMENT '板块领涨股';
ALTER TABLE prosperity_hot_sector ADD COLUMN lead_stock_change   DECIMAL(8,4)   DEFAULT NULL COMMENT '板块领涨股涨幅(%)';

-- prosperity_leader_candidate (mainline_reason + 6 finance columns)
ALTER TABLE prosperity_leader_candidate ADD COLUMN mainline_reason              VARCHAR(256)   DEFAULT NULL COMMENT '主线阶段未通过原因';
ALTER TABLE prosperity_leader_candidate ADD COLUMN revenue_yoy_min_4q          DECIMAL(10,4)  DEFAULT NULL COMMENT '近4季营收同比最小值(%)';
ALTER TABLE prosperity_leader_candidate ADD COLUMN deducted_netprofit_yoy_min_4q DECIMAL(10,4) DEFAULT NULL COMMENT '近4季扣非同比最小值(%)';
ALTER TABLE prosperity_leader_candidate ADD COLUMN gross_margin_avg_4q         DECIMAL(10,4)  DEFAULT NULL COMMENT '近4季毛利率均值(%)';
ALTER TABLE prosperity_leader_candidate ADD COLUMN debt_ratio_latest           DECIMAL(10,4)  DEFAULT NULL COMMENT '最新资产负债率(%)';
ALTER TABLE prosperity_leader_candidate ADD COLUMN operating_cashflow_sum_4q   DECIMAL(20,2)  DEFAULT NULL COMMENT '近4季经营现金流合计(元)';
ALTER TABLE prosperity_leader_candidate ADD COLUMN roe_latest                  DECIMAL(10,4)  DEFAULT NULL COMMENT '最新 ROE(%)';

-- prosperity_pick_daily (memo + revenue_yoy_min_3q)
ALTER TABLE prosperity_pick_daily ADD COLUMN memo                 TEXT         DEFAULT NULL COMMENT '板块归属备注';
ALTER TABLE prosperity_pick_daily ADD COLUMN revenue_yoy_min_3q  DECIMAL(10,4) DEFAULT NULL COMMENT '近3季度营收同比最小值(%)';

-- invest_prosperity_pick (7 new紫苏叶/九维 columns)
ALTER TABLE invest_prosperity_pick ADD COLUMN chain_position  TEXT         DEFAULT NULL COMMENT '紫苏叶产业链定位 JSON';
ALTER TABLE invest_prosperity_pick ADD COLUMN nine_dimension  TEXT         DEFAULT NULL COMMENT '高景气九维 JSON';
ALTER TABLE invest_prosperity_pick ADD COLUMN baostock_data   MEDIUMTEXT   DEFAULT NULL COMMENT 'baostock 原始数据包 JSON';
ALTER TABLE invest_prosperity_pick ADD COLUMN moat_score      INT          DEFAULT NULL COMMENT '护城河评分 1-10';
ALTER TABLE invest_prosperity_pick ADD COLUMN verdict         VARCHAR(64)  DEFAULT NULL COMMENT '紫苏叶判定';
ALTER TABLE invest_prosperity_pick ADD COLUMN elapsed_ms      INT          DEFAULT NULL COMMENT '分析耗时 ms';
ALTER TABLE invest_prosperity_pick ADD COLUMN report_html     MEDIUMTEXT   DEFAULT NULL COMMENT '报告详情 HTML';
