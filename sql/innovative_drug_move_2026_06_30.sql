-- ============================================================
-- 第二步：把已在 tech_vc 的 4 只股票移动到 innovative_drug
-- ENUM 已在 第一步 ALTER 加入 innovative_drug，幂等可重复
-- ============================================================

-- 600276.SH 恒瑞医药：数据已正确，只需移动 + 补 market_cap/gain/order
UPDATE invest_stock_pool SET
  pool_type           = 'innovative_drug',
  current_market_cap  = 3230.33,
  ytd_gain_pct        = -18.02,
  profit_level        = 'medium',
  display_order       = 7
WHERE stock_code = '600276.SH';

-- 688235.SH 百济神州：数据已正确，只需移动 + 补字段
UPDATE invest_stock_pool SET
  pool_type           = 'innovative_drug',
  current_market_cap  = 3578.02,
  ytd_gain_pct        = -13.59,
  profit_level        = 'high',
  display_order       = 6
WHERE stock_code = '688235.SH';

-- 301080.SZ 百普赛斯：rev 数据是真实财报，需刷为图片估算值 + 移动
UPDATE invest_stock_pool SET
  pool_type           = 'innovative_drug',
  rev_2023            = 5.44,
  rev_2024            = 6.45,
  rev_2025            = 8.38,
  current_market_cap  = 73.09,
  ytd_gain_pct        = -18.13,
  profit_level        = 'high',
  display_order       = 3
WHERE stock_code = '301080.SZ';

-- 688321.SH 微芯生物：rev_2024 修正 (6.85→6.58) + 移动
UPDATE invest_stock_pool SET
  pool_type           = 'innovative_drug',
  rev_2024            = 6.58,
  current_market_cap  = 106.18,
  ytd_gain_pct        = -17.87,
  profit_level        = 'high',
  display_order       = 5
WHERE stock_code = '688321.SH';