package com.quant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.schema-guard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TechAiSchemaGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TechAiSchemaGuard.class);

    private final JdbcTemplate jdbcTemplate;

    public TechAiSchemaGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            runInternal(args);
        } catch (Exception e) {
            // TechAiSchemaGuard 是为老 DB 做 schema 补齐的；进行中的"持仓/告警字段迁出 invest_stock_pool"重构里，
            // 这些列已被 SchemaInitializer 删除、InvestStockPool entity 已删除对应字段——
            // 此时再 ADD COLUMN 会因 `AFTER 不存在的列` 报 Unknown column，整段 run() 拖死整个 Spring Boot 启动。
            // 这里只 log warn，不抛异常，让主应用继续起来。
            log.warn("TechAiSchemaGuard.run() 失败，已忽略（schema 已迁移/或运行中）。错误: {}", e.getMessage());
        }
    }

    private void runInternal(ApplicationArguments args) {
        // 历史：曾在此维护 invest_stock_pool 的 27 列 alert/position/策略参数列（add_count/alert_* 等）。
        // 这些列已于 2026-06-30 迁出到 invest_position_common 表（pool_type='tech_ai'/'invest'/'potential'），
        // InvestStockPool entity 已删除对应字段，SchemaInitializer.dropPositionColumnsFromPool 已 DROP 这些列。
        // 此处不再是 schema 防御点 —— 集中到 SchemaInitializer.ensureInvestPositionCommon() 处理。
        // 如果有人恢复这些列，请同步加回到 SchemaInitializer 的 dropPositionColumnsFromPool("invest_stock_pool", ...) 列表。

        ensurePositionFillTable();
        ensurePotentialPoolTable();
        ensurePotentialPositionFillTable();
        ensureTechAiPoolTableColumns();
        ensureTechAiPositionFillTable();
        migrateLegacyTechAiPool();
    }

    private void widenPoolTypeEnum() {
        // potential pool now uses its own table (potential_pool), no longer needs enum widening.
        // Keeping this method as a no-op for backward compatibility if the column was already widened.
    }

    private void ensurePositionFillTable() {
        if (tableExists("invest_position_fill")) {
            return;
        }
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `invest_position_fill` (
                  `id` bigint NOT NULL AUTO_INCREMENT,
                  `pool_id` int NOT NULL COMMENT 'invest_stock_pool.id',
                  `stock_code` varchar(20) NOT NULL,
                  `action` varchar(10) NOT NULL COMMENT 'open/add/reduce/clear',
                  `price` decimal(10,2) NOT NULL,
                  `lots` decimal(10,2) NOT NULL,
                  `amount` decimal(14,2) DEFAULT NULL,
                  `fee` decimal(10,2) DEFAULT NULL,
                  `note` varchar(255) DEFAULT NULL,
                  `filled_at` datetime NOT NULL,
                  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (`id`),
                  KEY `idx_pool` (`pool_id`),
                  KEY `idx_code` (`stock_code`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='科技AI 持仓成交流水'
                """);
        log.info("Created missing table: invest_position_fill");
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                """, Integer.class, tableName);
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """, Integer.class, tableName, columnName);
        return count != null && count > 0;
    }

    private void ensurePotentialPoolTable() {
        if (tableExists("potential_pool")) {
            return;
        }
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `potential_pool` (
                  `id` int NOT NULL AUTO_INCREMENT,
                  `stock_code` varchar(20) NOT NULL COMMENT '股票代码',
                  `stock_name` varchar(255) DEFAULT NULL COMMENT '股票名称',
                  `status` varchar(10) DEFAULT 'watching' COMMENT 'watching/holding/exited',
                  `memo` text COMMENT '备注',
                  `alert_minute_1m_pct` decimal(8,2) DEFAULT NULL COMMENT '1分钟涨跌幅告警阈值(%)',
                  `alert_minute_5m_pct` decimal(8,2) DEFAULT NULL COMMENT '5分钟涨跌幅告警阈值(%)',
                  `alert_daily_pct` decimal(8,2) DEFAULT NULL COMMENT '当日涨跌幅告警阈值(%)',
                  `alert_three_day_pct` decimal(8,2) DEFAULT NULL COMMENT '3日涨跌幅告警阈值(%)',
                  `alert_turnover_ratio_pct` decimal(8,2) DEFAULT NULL COMMENT '换手率放大告警阈值(%)',
                  `entry_price` decimal(10,2) DEFAULT NULL COMMENT '首仓买入价',
                  `position_lots` decimal(10,2) DEFAULT 0 COMMENT '当前持仓手数',
                  `avg_cost` decimal(10,2) DEFAULT NULL COMMENT '当前平均成本',
                  `total_invested` decimal(14,2) DEFAULT NULL COMMENT '当前持仓成本基础',
                  `add_count` int DEFAULT 0 COMMENT '加仓次数',
                  `last_add_price` decimal(10,2) DEFAULT NULL COMMENT '最近一次买入价',
                  `peak_price` decimal(10,2) DEFAULT NULL COMMENT '建仓后最高价',
                  `stop_price` decimal(10,2) DEFAULT NULL COMMENT '当前移动止损价',
                  `realized_pnl` decimal(14,2) DEFAULT 0 COMMENT '已实现盈亏',
                  `position_state` varchar(20) DEFAULT 'none' COMMENT 'none/holding/scaled/exited',
                  `take_profit_done` tinyint(1) DEFAULT 0 COMMENT '是否已执行目标减仓',
                  `opened_at` datetime DEFAULT NULL COMMENT '首次建仓时间',
                  `target_sell_price` decimal(10,2) DEFAULT NULL COMMENT '目标止盈价',
                  `add_step_pct` decimal(6,2) DEFAULT 10.00 COMMENT '加仓步长(%)',
                  `trail_pct` decimal(6,2) DEFAULT 10.00 COMMENT '移动止损回撤(%)',
                  `add_size_schedule` varchar(50) DEFAULT '1,1,1' COMMENT '每档加仓手数表',
                  `max_lots` decimal(10,2) DEFAULT NULL COMMENT '单票最大持仓手数',
                  `take_profit_pct` decimal(6,2) DEFAULT 50.00 COMMENT '到目标价减仓比例(%)',
                  `breakeven_after_tp` tinyint(1) DEFAULT 1 COMMENT '止盈后是否止损上移保本',
                  `time_stop_days` int DEFAULT NULL COMMENT '时间止损天数',
                  `use_atr` tinyint(1) DEFAULT 0 COMMENT '是否启用ATR自适应',
                  `atr_period` int DEFAULT 14 COMMENT 'ATR周期',
                  `atr_add_mult` decimal(6,2) DEFAULT 1.00 COMMENT 'ATR加仓倍数',
                  `atr_trail_mult` decimal(6,2) DEFAULT 2.00 COMMENT 'ATR止损倍数',
                  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
                  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (`id`),
                  UNIQUE KEY `uk_stock_code` (`stock_code`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='潜力监控股票池'
                """);
        log.info("Created missing table: potential_pool");
    }

    private void ensurePotentialPositionFillTable() {
        if (tableExists("potential_position_fill")) {
            return;
        }
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `potential_position_fill` (
                  `id` bigint NOT NULL AUTO_INCREMENT,
                  `pool_id` int NOT NULL COMMENT 'potential_pool.id',
                  `stock_code` varchar(20) NOT NULL,
                  `action` varchar(10) NOT NULL COMMENT 'open/add/reduce/clear',
                  `price` decimal(10,2) NOT NULL,
                  `lots` decimal(10,2) NOT NULL,
                  `amount` decimal(14,2) DEFAULT NULL,
                  `fee` decimal(10,2) DEFAULT NULL,
                  `note` varchar(255) DEFAULT NULL,
                  `filled_at` datetime NOT NULL,
                  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (`id`),
                  KEY `idx_pool` (`pool_id`),
                  KEY `idx_code` (`stock_code`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='潜力监控持仓成交流水'
                """);
        log.info("Created missing table: potential_position_fill");
    }

    /**
     * tech_ai_pool 表的列严格对齐 TechAiPool entity（7 字段）。
     * <p>
     * 持仓/告警/策略参数已迁至 invest_position_common（pool_type='tech_ai'），
     * 不再在本表保留冗余列。表 schema 与 entity 漂移是历史 bug（2026-06-30 报
     * Unknown column 'tap1_0.add_count'/'status' 根因）。
     * <p>
     * 旧版本表可能残留 alert_xxx / add_count 等列，但已不被 entity 引用，留着无害；
     * 如需清理可手动 DROP COLUMN，参考 SchemaInitializer.dropPositionColumnsFromPool 的同款模式。
     */
    private void ensureTechAiPoolTableColumns() {
        if (!tableExists("tech_ai_pool")) {
            jdbcTemplate.execute("""
                    CREATE TABLE `tech_ai_pool` (
                      `id` int NOT NULL AUTO_INCREMENT,
                      `stock_code` varchar(20) NOT NULL COMMENT '股票代码',
                      `stock_name` varchar(255) DEFAULT NULL COMMENT '股票名称',
                      `status` varchar(10) DEFAULT 'watching' COMMENT 'watching/holding/exited',
                      `memo` text COMMENT '备注',
                      `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
                      `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (`id`),
                      UNIQUE KEY `uk_stock_code` (`stock_code`)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='短线AI监控股票池（独立于龙江投资 invest_stock_pool）'
                    """);
            log.info("Created missing table: tech_ai_pool");
            return;
        }
        // 表已存在：补齐 entity 期望的列（如 status 在 2026-06-30 之前的 schema 里缺失）
        Map<String, String> required = new LinkedHashMap<>();
        required.put("status", "ALTER TABLE tech_ai_pool ADD COLUMN status varchar(10) DEFAULT 'watching' COMMENT 'watching/holding/exited'");
        required.forEach((colName, ddl) -> {
            if (!columnExists("tech_ai_pool", colName)) {
                try {
                    jdbcTemplate.execute(ddl);
                    log.info("Added missing tech_ai_pool column: {}", colName);
                } catch (Exception e) {
                    log.warn("Add column tech_ai_pool.{} 失败 (可忽略): {}", colName, e.getMessage());
                }
            }
        });
    }

    private void ensureTechAiPositionFillTable() {
        if (tableExists("tech_ai_position_fill")) {
            return;
        }
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `tech_ai_position_fill` (
                  `id` bigint NOT NULL AUTO_INCREMENT,
                  `pool_id` int NOT NULL COMMENT 'tech_ai_pool.id',
                  `stock_code` varchar(20) NOT NULL,
                  `action` varchar(10) NOT NULL COMMENT 'open/add/reduce/clear',
                  `price` decimal(10,2) NOT NULL,
                  `lots` decimal(10,2) NOT NULL,
                  `amount` decimal(14,2) DEFAULT NULL,
                  `fee` decimal(10,2) DEFAULT NULL,
                  `note` varchar(255) DEFAULT NULL,
                  `filled_at` datetime NOT NULL,
                  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (`id`),
                  KEY `idx_pool` (`pool_id`),
                  KEY `idx_code` (`stock_code`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='短线AI监控持仓成交流水'
                """);
        log.info("Created missing table: tech_ai_position_fill");
    }

    /**
     * 把老的 invest_stock_pool 中 pool_type='tech_ai' 的条目以及对应的 invest_position_fill 流水
     * 一次性拷贝到 tech_ai_pool / tech_ai_position_fill，拷贝完成后老条目改 pool_type='tech_ai_migrated'
     * 标记为已迁出，避免下次启动重复迁移。
     */
    private void migrateLegacyTechAiPool() {
        if (!tableExists("invest_stock_pool") || !tableExists("tech_ai_pool")) {
            return;
        }
        Integer pending = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM invest_stock_pool
                WHERE pool_type = 'tech_ai' AND id NOT IN (
                  SELECT id FROM tech_ai_pool
                )
                """, Integer.class);
        if (pending == null || pending == 0) {
            return;
        }
        log.info("migrateLegacyTechAiPool: 准备迁移 {} 条短线 AI 监控记录", pending);

        // 1) 拷贝主表（保留原 id 便于流水对齐）
        jdbcTemplate.execute("""
                INSERT INTO tech_ai_pool (
                  id, stock_code, stock_name, status, memo, alert_state, last_alert_at,
                  alert_minute_1m_pct, alert_minute_5m_pct, alert_daily_pct, alert_three_day_pct,
                  alert_turnover_ratio_pct, entry_price, position_lots, avg_cost, total_invested,
                  add_count, last_add_price, peak_price, stop_price, realized_pnl, position_state,
                  take_profit_done, opened_at, target_sell_price,
                  add_step_pct, trail_pct, add_size_schedule, max_lots, take_profit_pct,
                  breakeven_after_tp, time_stop_days, use_atr, atr_period, atr_add_mult, atr_trail_mult,
                  created_at, updated_at
                )
                SELECT
                  id, stock_code, stock_name, status, memo, alert_state, last_alert_at,
                  alert_minute_1m_pct, alert_minute_5m_pct, alert_daily_pct, alert_three_day_pct,
                  alert_turnover_ratio_pct, entry_price, position_lots, avg_cost, total_invested,
                  add_count, last_add_price, peak_price, stop_price, realized_pnl, position_state,
                  take_profit_done, opened_at, target_sell_price,
                  add_step_pct, trail_pct, add_size_schedule, max_lots, take_profit_pct,
                  breakeven_after_tp, time_stop_days, use_atr, atr_period, atr_add_mult, atr_trail_mult,
                  created_at, updated_at
                FROM invest_stock_pool
                WHERE pool_type = 'tech_ai'
                ON DUPLICATE KEY UPDATE stock_code = VALUES(stock_code)
                """);

        // 2) 拷贝成交流水（pool_id 一致）
        if (tableExists("invest_position_fill") && tableExists("tech_ai_position_fill")) {
            jdbcTemplate.execute("""
                    INSERT INTO tech_ai_position_fill (
                      id, pool_id, stock_code, action, price, lots, amount, fee, note, filled_at, created_at
                    )
                    SELECT
                      id, pool_id, stock_code, action, price, lots, amount, fee, note, filled_at, created_at
                    FROM invest_position_fill
                    WHERE pool_id IN (SELECT id FROM invest_stock_pool WHERE pool_type = 'tech_ai')
                      AND id NOT IN (SELECT id FROM tech_ai_position_fill)
                    """);
        }

        // 3) 标记老条目已迁移，避免下次启动重复迁移（用新 pool_type 区别）
        jdbcTemplate.update("""
                UPDATE invest_stock_pool
                SET pool_type = 'tech_ai_migrated'
                WHERE pool_type = 'tech_ai'
                """);

        log.info("migrateLegacyTechAiPool: 迁移完成，旧条目标记为 tech_ai_migrated");
    }
}
