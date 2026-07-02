-- ============================================================
-- 2026-07-02 池子重构迁移：tech_ai_pool → invest_stock_pool
-- 目标：3 个池子（quality / tech_ai / innovative_drug）共用 invest_stock_pool
-- ============================================================
-- 用途：production DB 首次执行（dev/staging 已经跑过）
-- 备份：会自动建 tech_ai_pool_backup_20260702 / invest_position_common_backup_20260702
-- 可回滚：trash/restore 表结构靠 init DB dump
-- ============================================================

START TRANSACTION;

-- 1) 备份 tech_ai_pool 完整数据
CREATE TABLE IF NOT EXISTS tech_ai_pool_backup_20260702 AS
  SELECT * FROM tech_ai_pool;

-- 2) tech_ai_pool → invest_stock_pool with pool_type='tech_ai'
--    schema 差异：status 写到 memo 头部，结构化数值列留 NULL（用 Python 解析 memo 回填）
INSERT INTO invest_stock_pool (
  stock_code, stock_name, pool_type, memo,
  rev_2023, rev_2024, rev_2025,
  revenue_forecast_y0, revenue_forecast_y1, revenue_forecast_y2,
  q1_gross_margin, q1_net_margin, q1_revenue_growth,
  min_ps_5y, current_market_cap, ytd_gain_pct,
  display_order, created_at, updated_at
)
SELECT
  stock_code,
  stock_name,
  'tech_ai' AS pool_type,
  CONCAT('[', COALESCE(NULLIF(status, ''), 'watching'), '] ',
         COALESCE(memo, '')) AS memo,
  NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
  100, created_at, updated_at
FROM tech_ai_pool
ON DUPLICATE KEY UPDATE
  stock_name = VALUES(stock_name),
  memo = VALUES(memo),
  pool_type = 'tech_ai',
  updated_at = CURRENT_TIMESTAMP;

-- 3) 备份 invest_position_common.tech_ai 行（含 14 条脏数据）
CREATE TABLE IF NOT EXISTS invest_position_common_backup_20260702 AS
  SELECT * FROM invest_position_common WHERE pool_type='tech_ai';

-- 4) 清掉 tech_ai 14 条 common 脏数据（与新 33 只 invest_stock_pool tech_ai 行不匹配的孤立行）
DELETE FROM invest_position_common
WHERE pool_type='tech_ai'
  AND stock_code COLLATE utf8mb4_unicode_ci NOT IN (
    SELECT stock_code FROM invest_stock_pool WHERE pool_type='tech_ai'
  );

-- 5) 删 tech_vc 22 条老数据
DELETE FROM invest_stock_pool WHERE pool_type='tech_vc';

-- 6) 删 tech_ai_position_fill (空表)
DROP TABLE IF EXISTS tech_ai_position_fill;

-- 7) 删 tech_ai_pool
DROP TABLE IF EXISTS tech_ai_pool;

-- 8) ENUM 收紧
ALTER TABLE invest_stock_pool
  MODIFY COLUMN pool_type ENUM('quality','tech_ai','innovative_drug') NOT NULL;

-- 9) 验证
SELECT 'final pool_type distribution' AS step;
SELECT pool_type, COUNT(*) AS cnt FROM invest_stock_pool GROUP BY pool_type;

COMMIT;

-- ============================================================
-- 阶段 1.5: 解析 memo 回填结构化字段
-- 跑：python3 /tmp/phase1_5_backfill.py
-- 该脚本包含 33 条截图提取的数据，UPDATE 33 行的 rev_2023..ytd_gain_pct 列
-- ============================================================
