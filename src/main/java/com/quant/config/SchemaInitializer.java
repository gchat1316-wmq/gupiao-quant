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
        ensureInvestAlertTable();
        ensureInvestBigYangSignalTable();
        ensureStockAnalysisTable();
        ensurePdfPathColumn();
        ensureStockAnalysisUnifiedColumns();
        ensureProsperityPickNewColumns();
        ensureInvestStockPoolSnapshotColumns();
        ensureProsperityHotSectorAStockColumns();
        ensureProsperityLeaderMainlineReason();
        ensureIndustryResearchTables();
    }

    /**
     * 产业投研模块 4 张表
     */
    private void ensureIndustryResearchTables() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS industry_research_category (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    code VARCHAR(50) NOT NULL UNIQUE,
                    name VARCHAR(100) NOT NULL,
                    icon VARCHAR(50) DEFAULT NULL,
                    parent_id BIGINT DEFAULT NULL,
                    sort_order INT NOT NULL DEFAULT 0,
                    enabled TINYINT NOT NULL DEFAULT 1,
                    description VARCHAR(500) DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_ir_cat_parent (parent_id),
                    INDEX idx_ir_cat_sort (sort_order, enabled)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS industry_research_article (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    category_id BIGINT NOT NULL,
                    slug VARCHAR(80) NOT NULL UNIQUE,
                    title VARCHAR(200) NOT NULL,
                    subtitle VARCHAR(500) DEFAULT NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'draft',
                    version INT NOT NULL DEFAULT 1,
                    update_date DATE DEFAULT NULL,
                    source_summary VARCHAR(500) DEFAULT NULL,
                    cover_image VARCHAR(500) DEFAULT NULL,
                    tags VARCHAR(500) DEFAULT NULL,
                    view_count INT NOT NULL DEFAULT 0,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_ir_article_cat (category_id, status),
                    INDEX idx_ir_article_slug (slug)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS industry_research_section (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    article_id BIGINT NOT NULL,
                    section_key VARCHAR(50) NOT NULL,
                    section_title VARCHAR(100) NOT NULL,
                    section_order INT NOT NULL DEFAULT 0,
                    content_type VARCHAR(20) NOT NULL DEFAULT 'mixed',
                    content_json LONGTEXT NOT NULL,
                    source VARCHAR(500) DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_ir_section (article_id, section_key),
                    INDEX idx_ir_section_article (article_id, section_order)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS industry_research_task (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    category_id BIGINT NOT NULL,
                    article_id BIGINT DEFAULT NULL,
                    task_name VARCHAR(200) NOT NULL,
                    keyword VARCHAR(500) DEFAULT NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'pending',
                    stage VARCHAR(30) NOT NULL DEFAULT 'init',
                    progress INT NOT NULL DEFAULT 0,
                    total_reports INT DEFAULT NULL,
                    news_count INT DEFAULT NULL,
                    error_message TEXT DEFAULT NULL,
                    log TEXT DEFAULT NULL,
                    started_at DATETIME DEFAULT NULL,
                    finished_at DATETIME DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_ir_task_cat (category_id, status),
                    INDEX idx_ir_task_status (status, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            // 初始菜单 6 个产业
            jdbc.update("""
                INSERT INTO industry_research_category (code, name, icon, sort_order, enabled, description)
                VALUES ('ai-compute','AI 算力产业链','🧠',10,1,'NVIDIA / 光模块 / PCB / HBM / 服务器全链条深度'),
                       ('semiconductor','半导体设备','🔬',20,1,'光刻机 / 刻蚀 / 薄膜沉积 / 封测设备'),
                       ('new-energy','新能源车','⚡',30,1,'电池 / 电机 / 电控 / 整车'),
                       ('biotech','创新药','💊',40,1,'ADC / GLP-1 / 双抗 / 出海'),
                       ('consumer','新消费','🍵',50,1,'茶饮 / 咖啡 / 化妆品 / 零食'),
                       ('robotics','人形机器人','🤖',60,1,'丝杠 / 谐波 / 传感器 / 整机')
                ON DUPLICATE KEY UPDATE name = VALUES(name), icon = VALUES(icon), sort_order = VALUES(sort_order)
                """);
            log.info("industry_research 模块 4 张表已就绪 + 6 个产业菜单已初始化");
        } catch (Exception e) {
            log.warn("检查 industry_research 表失败 (可忽略): {}", e.getMessage());
        }
    }

    private void ensureInvestAlertTable() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS invest_alert (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    stock_code VARCHAR(20) NOT NULL,
                    signal_type VARCHAR(30) NOT NULL,
                    level INT DEFAULT NULL,
                    title VARCHAR(200) DEFAULT NULL,
                    content TEXT DEFAULT NULL,
                    trigger_price DECIMAL(10,2) DEFAULT NULL,
                    trigger_at DATETIME DEFAULT NULL,
                    channels VARCHAR(100) DEFAULT NULL,
                    pushed TINYINT DEFAULT 0,
                    read_flag TINYINT DEFAULT 0,
                    user_id INT DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_invest_alert_stock_signal (stock_code, signal_type, trigger_at),
                    INDEX idx_invest_alert_signal_read (signal_type, read_flag, trigger_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            log.info("invest_alert 表已就绪");
        } catch (Exception e) {
            log.warn("检查 invest_alert 表失败 (可忽略): {}", e.getMessage());
        }
    }

    private void ensureInvestBigYangSignalTable() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS invest_big_yang_signal (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    source_pool_id INT DEFAULT NULL,
                    source_pool_type VARCHAR(20) DEFAULT NULL,
                    stock_code VARCHAR(20) NOT NULL,
                    stock_name VARCHAR(64) NOT NULL,
                    signal_status VARCHAR(20) NOT NULL,
                    limit_up_streak INT NOT NULL,
                    first_limit_up_date DATE NOT NULL,
                    last_limit_up_date DATE NOT NULL,
                    base_start_price DECIMAL(10,2) DEFAULT NULL,
                    first_limit_up_open_price DECIMAL(10,2) DEFAULT NULL,
                    first_limit_up_close_price DECIMAL(10,2) DEFAULT NULL,
                    last_limit_up_close_price DECIMAL(10,2) DEFAULT NULL,
                    trigger_price DECIMAL(10,2) DEFAULT NULL,
                    trigger_date DATE DEFAULT NULL,
                    status_reason VARCHAR(255) DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_big_yang_stock_first_day (stock_code, first_limit_up_date),
                    INDEX idx_big_yang_status_updated (signal_status, updated_at),
                    INDEX idx_big_yang_source_pool (source_pool_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            log.info("invest_big_yang_signal 表已就绪");
        } catch (Exception e) {
            log.warn("检查 invest_big_yang_signal 表失败 (可忽略): {}", e.getMessage());
        }
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
