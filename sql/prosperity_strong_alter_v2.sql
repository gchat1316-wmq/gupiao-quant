-- ============================================================
-- 高景气强势股选股 - 增量补丁 v2
-- 新增: 过滤链字段(leader_candidate 表) + 自动触发
-- ============================================================

SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS add_prosperity_leader_column $$
CREATE PROCEDURE add_prosperity_leader_column(
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

-- 1. 给 prosperity_leader_candidate 加过滤链字段
CALL add_prosperity_leader_column('finance_score', '`finance_score` decimal(8,2) DEFAULT NULL COMMENT ''Step3财务评分'' AFTER `filter_reason`');
CALL add_prosperity_leader_column('finance_passed', '`finance_passed` tinyint(1) DEFAULT NULL COMMENT ''Step3财务硬筛是否通过'' AFTER `finance_score`');
CALL add_prosperity_leader_column('finance_reason', '`finance_reason` varchar(256) DEFAULT NULL COMMENT ''Step3未通过原因'' AFTER `finance_passed`');
CALL add_prosperity_leader_column('mainline_score', '`mainline_score` decimal(8,2) DEFAULT NULL COMMENT ''Step4主线评分'' AFTER `finance_reason`');
CALL add_prosperity_leader_column('mainline_passed', '`mainline_passed` tinyint(1) DEFAULT NULL COMMENT ''Step4主线判定是否通过'' AFTER `mainline_score`');
CALL add_prosperity_leader_column('final_stage', '`final_stage` varchar(20) DEFAULT NULL COMMENT ''最终到达阶段: leader_filter/finance_filter/mainline_filter/passed'' AFTER `mainline_passed`');

DROP PROCEDURE IF EXISTS add_prosperity_leader_column;
