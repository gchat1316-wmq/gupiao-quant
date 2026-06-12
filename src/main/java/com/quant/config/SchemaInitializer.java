package com.quant.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时检查 + 建表 (避免依赖 ddl-auto)
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SchemaInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(String... args) {
        ensureStockAnalysisTable();
        ensurePdfPathColumn();
        ensureProsperityPickNewColumns();
    }

    private void ensurePdfPathColumn() {
        try {
            Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'stock_analysis_record'
                  AND column_name = 'pdf_path'
                """, Integer.class);
            if (count == null || count == 0) {
                jdbc.execute("ALTER TABLE stock_analysis_record ADD COLUMN pdf_path VARCHAR(255) DEFAULT NULL COMMENT 'PDF 文件路径'");
                log.info("stock_analysis_record.pdf_path 列已添加");
            }
        } catch (Exception e) {
            log.warn("检查 pdf_path 列失败 (可忽略, 可能表还不存在): {}", e.getMessage());
        }
    }

    private void ensureProsperityPickNewColumns() {
        String[][] columns = {
            {"chain_position", "TEXT", "紫苏叶产业链定位 JSON"},
            {"nine_dimension", "TEXT", "高景气九维 JSON"},
            {"baostock_data", "MEDIUMTEXT", "baostock 原始数据包 JSON"},
            {"moat_score", "INT", "护城河评分 1-10"},
            {"verdict", "VARCHAR(64)", "紫苏叶判定"},
            {"elapsed_ms", "INT", "分析耗时 ms"},
            {"report_html", "MEDIUMTEXT", "报告详情 HTML"}
        };
        for (String[] col : columns) {
            try {
                Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'invest_prosperity_pick' AND column_name = '" + col[0] + "'",
                    Integer.class);
                if (count == null || count == 0) {
                    String alterSql = "ALTER TABLE invest_prosperity_pick ADD COLUMN " + col[0] + " " + col[1] + " DEFAULT NULL COMMENT '" + col[2] + "'";
                    jdbc.execute(alterSql);
                    log.info("invest_prosperity_pick.{} 列已添加", col[0]);
                }
            } catch (Exception e) {
                log.warn("检查 invest_prosperity_pick.{} 列失败 (可忽略): {}", col[0], e.getMessage());
            }
        }
    }

    private void ensureStockAnalysisTable() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS stock_analysis_record (
                id              BIGINT PRIMARY KEY AUTO_INCREMENT,
                stock_code      VARCHAR(16) NOT NULL,
                stock_code_raw  VARCHAR(16) NOT NULL,
                stock_name      VARCHAR(64) DEFAULT NULL,
                method          VARCHAR(32) NOT NULL DEFAULT 'full',
                years           INT NOT NULL DEFAULT 2,
                lite            TINYINT(1) NOT NULL DEFAULT 1,
                quote_days      INT NOT NULL DEFAULT 60,
                status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                error_message   VARCHAR(1024) DEFAULT NULL,
                current_price   DECIMAL(18,4) DEFAULT NULL,
                verdict         VARCHAR(64) DEFAULT NULL,
                moat_score      INT DEFAULT NULL,
                elapsed_ms      INT DEFAULT NULL,
                result_json     LONGTEXT DEFAULT NULL,
                submitted_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                started_at      DATETIME(3) DEFAULT NULL,
                finished_at     DATETIME(3) DEFAULT NULL,
                pdf_path        VARCHAR(255) DEFAULT NULL COMMENT '生成的 PDF 文件相对路径',
                INDEX idx_stock_code (stock_code),
                INDEX idx_status (status),
                INDEX idx_submitted_at (submitted_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        try {
            jdbc.execute(ddl);
            log.info("stock_analysis_record 表已就绪");
        } catch (Exception e) {
            log.error("建表失败", e);
        }
    }
}
