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
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("alert_minute_1m_pct", "ALTER TABLE `invest_stock_pool` ADD COLUMN `alert_minute_1m_pct` decimal(8,2) DEFAULT NULL COMMENT '科技AI 1分钟涨跌幅告警阈值(%)' AFTER `last_alert_at`");
        columns.put("alert_minute_5m_pct", "ALTER TABLE `invest_stock_pool` ADD COLUMN `alert_minute_5m_pct` decimal(8,2) DEFAULT NULL COMMENT '科技AI 5分钟涨跌幅告警阈值(%)' AFTER `alert_minute_1m_pct`");
        columns.put("alert_daily_pct", "ALTER TABLE `invest_stock_pool` ADD COLUMN `alert_daily_pct` decimal(8,2) DEFAULT NULL COMMENT '科技AI 当日涨跌幅告警阈值(%)' AFTER `alert_minute_5m_pct`");
        columns.put("alert_three_day_pct", "ALTER TABLE `invest_stock_pool` ADD COLUMN `alert_three_day_pct` decimal(8,2) DEFAULT NULL COMMENT '科技AI 3日涨跌幅告警阈值(%)' AFTER `alert_daily_pct`");
        columns.put("alert_turnover_ratio_pct", "ALTER TABLE `invest_stock_pool` ADD COLUMN `alert_turnover_ratio_pct` decimal(8,2) DEFAULT NULL COMMENT '科技AI 换手率放大告警阈值，占5日均值比例(%)' AFTER `alert_three_day_pct`");

        columns.forEach((columnName, ddl) -> {
            if (!columnExists(columnName)) {
                jdbcTemplate.execute(ddl);
                log.info("Added missing invest_stock_pool column: {}", columnName);
            }
        });
    }

    private boolean columnExists(String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'invest_stock_pool'
                  AND column_name = ?
                """, Integer.class, columnName);
        return count != null && count > 0;
    }
}
