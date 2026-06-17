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
        ensureLynchInvestTables();
        ensureStockAnalysisTable();
        ensurePdfPathColumn();
        ensureStockAnalysisUnifiedColumns();
        ensureProsperityPickNewColumns();
        ensureInvestStockPoolSnapshotColumns();
        ensureProsperityHotSectorAStockColumns();
        ensureProsperityLeaderMainlineReason();
    }

    private void ensureLynchInvestTables() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS invest_lynch_watchlist (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    stock_code VARCHAR(20) NOT NULL UNIQUE,
                    stock_name VARCHAR(64) NOT NULL,
                    display_order INT DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_lynch_watchlist_order (display_order, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS invest_lynch_analysis_record (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    stock_code VARCHAR(20) NOT NULL,
                    stock_name VARCHAR(64) NOT NULL,
                    analysis_date DATE NOT NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'collecting',
                    peg_value DECIMAL(10,2) DEFAULT NULL,
                    peg_rating VARCHAR(32) DEFAULT NULL,
                    conclusion VARCHAR(500) DEFAULT NULL,
                    report_markdown LONGTEXT DEFAULT NULL,
                    raw_snapshot_json LONGTEXT DEFAULT NULL,
                    error_message VARCHAR(1000) DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_lynch_analysis_stock_date (stock_code, analysis_date),
                    INDEX idx_lynch_analysis_status (status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            log.info("lynch invest tables are ready");
        } catch (Exception e) {
            log.warn("检查 lynch invest 表失败 (可忽略): {}", e.getMessage());
        }
    }

    private void ensureProsperityHotSectorAStockColumns() {
        String[][] columns = {
            {"up_count", "INT", "板块上涨家数"},
            {"down_count", "INT", "板块下跌家数"},
            {"lead_stock", "VARCHAR(64)", "板块领涨股"},
            {"lead_stock_change", "DECIMAL(8,4)", "板块领涨股涨幅(%)"}
        };
        for (String[] col : columns) {
            try {
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'prosperity_hot_sector' AND column_name = '" + col[0] + "'",
                        Integer.class);
                if (count == null || count == 0) {
                    jdbc.execute("ALTER TABLE prosperity_hot_sector ADD COLUMN " + col[0] + " " + col[1] + " DEFAULT NULL COMMENT '" + col[2] + "'");
                    log.info("prosperity_hot_sector.{} 列已添加", col[0]);
                }
            } catch (Exception e) {
                log.warn("检查 prosperity_hot_sector.{} 列失败 (可忽略): {}", col[0], e.getMessage());
            }
        }
    }

    private void ensureInvestStockPoolSnapshotColumns() {
        String[][] columns = {
            {"display_order", "INT", "股票池展示顺序"},
            {"current_market_cap", "DECIMAL(12,2)", "当前市值快照(亿元)"},
            {"ytd_gain_pct", "DECIMAL(8,2)", "今年涨幅快照(%)"},
            {"pool_data_updated_at", "DATETIME", "股票池数据刷新时间"},
            {"pool_update_error", "VARCHAR(1000)", "股票池数据刷新错误"}
        };
        for (String[] col : columns) {
            try {
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'invest_stock_pool' AND column_name = '" + col[0] + "'",
                        Integer.class);
                if (count == null || count == 0) {
                    jdbc.execute("ALTER TABLE invest_stock_pool ADD COLUMN " + col[0] + " " + col[1] + " DEFAULT NULL COMMENT '" + col[2] + "'");
                    log.info("invest_stock_pool.{} 列已添加", col[0]);
                }
            } catch (Exception e) {
                log.warn("检查 invest_stock_pool.{} 列失败 (可忽略): {}", col[0], e.getMessage());
            }
        }
    }

    private void ensureProsperityLeaderMainlineReason() {
        try {
            Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'prosperity_leader_candidate'
                  AND column_name = 'mainline_reason'
                """, Integer.class);
            if (count == null || count == 0) {
                jdbc.execute("ALTER TABLE prosperity_leader_candidate ADD COLUMN mainline_reason VARCHAR(256) DEFAULT NULL COMMENT '主线阶段未通过原因'");
                log.info("prosperity_leader_candidate.mainline_reason 列已添加");
            }
        } catch (Exception e) {
            log.warn("检查 prosperity_leader_candidate.mainline_reason 列失败 (可忽略): {}", e.getMessage());
        }
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

    private void ensureStockAnalysisUnifiedColumns() {
        String[][] columns = {
                {"source_payload_json", "LONGTEXT", "统一多源原始数据包 JSON"},
                {"report_html", "LONGTEXT", "统一富报告 HTML"}
        };
        for (String[] col : columns) {
            try {
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'stock_analysis_record' AND column_name = '" + col[0] + "'",
                        Integer.class);
                if (count == null || count == 0) {
                    jdbc.execute("ALTER TABLE stock_analysis_record ADD COLUMN " + col[0] + " " + col[1] + " DEFAULT NULL COMMENT '" + col[2] + "'");
                    log.info("stock_analysis_record.{} 列已添加", col[0]);
                }
            } catch (Exception e) {
                log.warn("检查 stock_analysis_record.{} 列失败 (可忽略): {}", col[0], e.getMessage());
            }
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
                source_payload_json LONGTEXT DEFAULT NULL COMMENT '统一多源原始数据包 JSON',
                report_html     LONGTEXT DEFAULT NULL COMMENT '统一富报告 HTML',
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
