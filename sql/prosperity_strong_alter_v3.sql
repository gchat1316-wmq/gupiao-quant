-- ============================================================
-- 热点选股 - 增量补丁 v3
-- 新增: prosperity_leader_candidate.mainline_reason
--       用来在"成分股过滤明细"里展示主线阶段未通过的具体原因
-- ============================================================

SET NAMES utf8mb4;

-- 检查 + 添加列(已存在则跳过)
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'prosperity_leader_candidate'
    AND column_name = 'mainline_reason'
);

SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `prosperity_leader_candidate` ADD COLUMN `mainline_reason` VARCHAR(256) DEFAULT NULL COMMENT ''Step4主线未通过原因'' AFTER `mainline_passed`',
  'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
