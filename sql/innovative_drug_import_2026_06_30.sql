-- ============================================================
-- 把 7 只 10倍PS 医药备选股导入 innovative_drug 池
-- 数据源：微信图片 2026.6.27 用户研究表
-- 估值由 InvestService.inferValuationRange() 在查询时按 10×PS 计算
-- ============================================================

-- 1. 扩 invest_stock_pool.pool_type ENUM，加入 innovative_drug
ALTER TABLE invest_stock_pool
  MODIFY COLUMN pool_type ENUM('quality','tech_vc','tech_ai','potential','innovative_drug')
  NOT NULL COMMENT '质量优选/科技风投/科技AI/潜力监控/创新药';

-- 2. UPDATE 已在 quality 的 3 只股票：移到 innovative_drug + 按图片数据刷新
UPDATE invest_stock_pool SET
  pool_type               = 'innovative_drug',
  rev_2023                = 3.71,
  rev_2024                = 4.27,
  rev_2025                = 5.26,
  revenue_forecast_y0     = 6.41,
  revenue_forecast_y1     = 7.85,
  revenue_forecast_y2     = 9.69,
  q1_gross_margin         = 56.34,
  q1_net_margin           = 25.86,
  q1_revenue_growth       = 50.40,
  min_ps_5y               = 7.78,
  current_market_cap      = 122.21,
  ytd_gain_pct            = 30.79,
  profit_level            = 'high',
  display_order           = 1
WHERE stock_code = '688222.SH';

UPDATE invest_stock_pool SET
  pool_type               = 'innovative_drug',
  rev_2023                = 4.03,
  rev_2024                = 5.34,
  rev_2025                = 6.96,
  revenue_forecast_y0     = 9.29,
  revenue_forecast_y1     = 10.97,
  revenue_forecast_y2     = 12.71,
  q1_gross_margin         = 67.71,
  q1_net_margin           = 21.84,
  q1_revenue_growth       = 31.15,
  min_ps_5y               = 3.80,
  current_market_cap      = 90.14,
  ytd_gain_pct            = 100.48,
  profit_level            = 'high',
  display_order           = 2
WHERE stock_code = '688179.SH';

UPDATE invest_stock_pool SET
  pool_type               = 'innovative_drug',
  rev_2023                = 7.80,
  rev_2024                = 8.88,
  rev_2025                = 10.35,
  revenue_forecast_y0     = 13.15,
  revenue_forecast_y1     = 17.65,
  revenue_forecast_y2     = 22.84,
  q1_gross_margin         = 55.97,
  q1_net_margin           = 15.02,
  q1_revenue_growth       = 25.35,
  min_ps_5y               = 3.06,
  current_market_cap      = 99.29,
  ytd_gain_pct            = 113.03,
  profit_level            = 'high',
  display_order           = 4
WHERE stock_code = '301580.SZ';

-- 3. INSERT 4 只新股票（无 UNIQUE 冲突：trade_stock_pool stock_code UNIQUE）
INSERT INTO invest_stock_pool
  (stock_code, stock_name, pool_type,
   rev_2023, rev_2024, rev_2025,
   revenue_forecast_y0, revenue_forecast_y1, revenue_forecast_y2,
   q1_gross_margin, q1_net_margin, q1_revenue_growth,
   min_ps_5y, current_market_cap, ytd_gain_pct,
   profit_level, display_order)
VALUES
  ('301080.SZ', '百普赛斯', 'innovative_drug',
   5.44, 6.45, 8.38,
   10.64, 13.26, 16.13,
   90.35, 19.63, 26.33,
   5.66, 73.09, -18.13,
   'high', 3),
  ('688235.SH', '百济神州', 'innovative_drug',
   174.23, 272.14, 382.25,
   450.50, 537.21, 616.08,
   87.61, 3.82, 31.00,
   5.58, 3578.02, -13.59,
   'high', 6),
  ('600276.SH', '恒瑞医药', 'innovative_drug',
   228.20, 279.85, 316.29,
   364.91, 422.90, 494.30,
   86.60, 28.02, 12.98,
   8.19, 3230.33, -18.02,
   'medium', 7),
  ('688321.SH', '微芯生物', 'innovative_drug',
   5.24, 6.58, 9.10,
   12.72, 17.74, 26.99,
   85.80, 11.00, 58.41,
   7.73, 106.18, -17.87,
   'high', 5);