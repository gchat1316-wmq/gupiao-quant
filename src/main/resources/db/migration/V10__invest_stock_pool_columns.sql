-- V10: invest_stock_pool ALTERs
-- Source: SchemaInitializer
--   - ensureInvestStockPoolSnapshotColumns()  → 6 snapshot columns (display_order / current_market_cap / ytd_gain_pct /
--                                                  pool_data_updated_at / pool_update_error / target_sell_price)
--   - ensureInvestStockPoolEnum()             → ENUM tighten to 3 legal values (quality/tech_ai/innovative_drug)

ALTER TABLE invest_stock_pool ADD COLUMN display_order         INT            DEFAULT NULL COMMENT '股票池展示顺序';
ALTER TABLE invest_stock_pool ADD COLUMN current_market_cap    DECIMAL(12,2)  DEFAULT NULL COMMENT '当前市值快照(亿元)';
ALTER TABLE invest_stock_pool ADD COLUMN ytd_gain_pct          DECIMAL(8,2)   DEFAULT NULL COMMENT '今年涨幅快照(%)';
ALTER TABLE invest_stock_pool ADD COLUMN pool_data_updated_at  DATETIME       DEFAULT NULL COMMENT '股票池数据刷新时间';
ALTER TABLE invest_stock_pool ADD COLUMN pool_update_error     VARCHAR(1000)  DEFAULT NULL COMMENT '股票池数据刷新错误';
ALTER TABLE invest_stock_pool ADD COLUMN target_sell_price     DECIMAL(10,2)  DEFAULT NULL COMMENT '希望卖出价';

ALTER TABLE invest_stock_pool
  MODIFY COLUMN pool_type
  ENUM('quality','tech_ai','innovative_drug')
  NOT NULL COMMENT '质量优选/科技AI/创新药';
