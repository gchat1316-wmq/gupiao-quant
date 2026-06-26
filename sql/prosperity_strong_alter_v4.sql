-- ============================================================
-- 热点选股 - 增量补丁 v4
-- 新增: prosperity_leader_candidate 财务硬筛字段(Step3 输出)
--   - revenue_yoy_min_4q          近4季营收同比最小值(%)
--   - deducted_netprofit_yoy_min_4q 近4季扣非同比最小值(%)
--   - gross_margin_avg_4q         近4季毛利率均值(%)
--   - debt_ratio_latest           最新资产负债率(%)
--   - operating_cashflow_sum_4q   近4季经营现金流合计(元)
--   - roe_latest                  最新 ROE(%)
--
-- 背景: 实体 ProsperityLeaderCandidate 已写入这些字段,
--       但 SQL 脚本漏建,运行时 SELECT 报 Unknown column。
--       SchemaInitializer 也会自动补这些列,本脚本给手工补建/新环境用。
-- ============================================================

SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS add_prosperity_leader_finance_column $$
CREATE PROCEDURE add_prosperity_leader_finance_column(
  IN p_column_name varchar(64),
  IN p_column_def varchar(512)
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'prosperity_leader_candidate'
      AND column_name = p_column_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `prosperity_leader_candidate` ADD COLUMN ', p_column_def);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

-- 财务硬筛字段(在 mainline_reason 之后追加,与实体字段顺序对齐)
CALL add_prosperity_leader_finance_column('revenue_yoy_min_4q', '`revenue_yoy_min_4q` DECIMAL(10,4) DEFAULT NULL COMMENT ''近4季营收同比最小值(%)'' AFTER `mainline_reason`');
CALL add_prosperity_leader_finance_column('deducted_netprofit_yoy_min_4q', '`deducted_netprofit_yoy_min_4q` DECIMAL(10,4) DEFAULT NULL COMMENT ''近4季扣非同比最小值(%)'' AFTER `revenue_yoy_min_4q`');
CALL add_prosperity_leader_finance_column('gross_margin_avg_4q', '`gross_margin_avg_4q` DECIMAL(10,4) DEFAULT NULL COMMENT ''近4季毛利率均值(%)'' AFTER `deducted_netprofit_yoy_min_4q`');
CALL add_prosperity_leader_finance_column('debt_ratio_latest', '`debt_ratio_latest` DECIMAL(10,4) DEFAULT NULL COMMENT ''最新资产负债率(%)'' AFTER `gross_margin_avg_4q`');
CALL add_prosperity_leader_finance_column('operating_cashflow_sum_4q', '`operating_cashflow_sum_4q` DECIMAL(20,2) DEFAULT NULL COMMENT ''近4季经营现金流合计(元)'' AFTER `debt_ratio_latest`');
CALL add_prosperity_leader_finance_column('roe_latest', '`roe_latest` DECIMAL(10,4) DEFAULT NULL COMMENT ''最新 ROE(%)'' AFTER `operating_cashflow_sum_4q`');

DROP PROCEDURE IF EXISTS add_prosperity_leader_finance_column;