package com.quant.config;

import com.quant.entity.User;
import com.quant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 启动时检查 + 建表 (避免依赖 ddl-auto)
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SchemaInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        ensureAuthUserTable();
        ensureSmsCodeTable();
        ensureEmailCodeTable();
        ensureLoginCodeTable();
        ensureAuditLogTable();
        ensureUserNotificationLogTable();
        bootstrapFirstAdmin();
        ensureXieboInvestTables();
        ensureInvestAlertTable();
        ensureInvestBigYangSignalTable();
        ensureStockAnalysisTable();
        ensurePdfPathColumn();
        ensureStockAnalysisUnifiedColumns();
        ensureProsperityPickNewColumns();
        ensureInvestStockPoolSnapshotColumns();
        ensureInvestStockPoolEnum();
        ensureProsperityHotSectorAStockColumns();
        ensureProsperityLeaderMainlineReason();
        ensureProsperityLeaderFinanceColumns();
        ensurePipelineRunTable();
        ensurePickDailyMemoColumn();
        ensurePickDailyRevenueYoyMin3q();
        ensureProsperityStockPoolTable();
        ensureProsperityStockPoolOwnerId();
        ensurePageViewStatTable();
        ensureUserDailyStatTable();
        ensureInvestPoolMetaTable();
        ensureInvestPoolMetaSeed();
        ensureWeeklyOpportunitySlotTable();
        ensureXieboWeeklyOpportunitySlotTable();
        ensureInvestPositionCommon();
        ensureMonitorFusionColumns();
        ensureInvestQuoteTable();
        ensureJournalTables();
    }

    // ── 认证相关表 ───────────────────────────────────────

    private void ensureAuthUserTable() {
        try {
            jdbc.execute("""
                CREATE TABLE auth_user (
                    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                    username      VARCHAR(50),
                    password_hash VARCHAR(255),
                    phone         VARCHAR(20)  UNIQUE,
                    email         VARCHAR(255) UNIQUE,
                    openid        VARCHAR(128) UNIQUE,
                    unionid       VARCHAR(128) UNIQUE,
                    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
                    disabled      BOOLEAN      NOT NULL DEFAULT FALSE,
                    last_login_at DATETIME,
                    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
                    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            log.info("auth_user 表已创建");
        } catch (Exception e) {
            // 表已存在，增量补列
            try { jdbc.execute("ALTER TABLE auth_user ADD COLUMN unionid VARCHAR(128) UNIQUE"); log.info("auth_user unionid 列已添加"); } catch (Exception ex) { log.debug("unionid 列已存在: {}", ex.getMessage()); }
            try { jdbc.execute("ALTER TABLE auth_user ADD COLUMN disabled BOOLEAN NOT NULL DEFAULT FALSE"); log.info("auth_user disabled 列已添加"); } catch (Exception ex) { log.debug("disabled 列已存在: {}", ex.getMessage()); }
            try { jdbc.execute("ALTER TABLE auth_user ADD COLUMN last_login_at DATETIME"); log.info("auth_user last_login_at 列已添加"); } catch (Exception ex) { log.debug("last_login_at 列已存在: {}", ex.getMessage()); }
            try { jdbc.execute("ALTER TABLE auth_user MODIFY COLUMN password_hash VARCHAR(255)"); log.info("auth_user password_hash 已改为 nullable"); } catch (Exception ex) { log.debug("password_hash 列调整: {}", ex.getMessage()); }
            try { jdbc.execute("ALTER TABLE auth_user MODIFY COLUMN username VARCHAR(50)"); log.info("auth_user username 已改为 nullable"); } catch (Exception ex) { log.debug("username 列调整: {}", ex.getMessage()); }
            try { jdbc.execute("ALTER TABLE auth_user ADD UNIQUE INDEX uk_username (username)"); log.info("auth_user username UNIQUE 已添加"); } catch (Exception ex) { log.debug("username UNIQUE 已存在: {}", ex.getMessage()); }
            // 用户信息中心新字段
            try { jdbc.execute("ALTER TABLE auth_user ADD COLUMN avatar_url VARCHAR(512)"); log.info("auth_user avatar_url 列已添加"); } catch (Exception ex) { log.debug("avatar_url 列已存在: {}", ex.getMessage()); }
            try { jdbc.execute("ALTER TABLE auth_user ADD COLUMN notify_wechat BOOLEAN NOT NULL DEFAULT TRUE"); log.info("auth_user notify_wechat 列已添加"); } catch (Exception ex) { log.debug("notify_wechat 列已存在: {}", ex.getMessage()); }
            try { jdbc.execute("ALTER TABLE auth_user ADD COLUMN notify_sms BOOLEAN NOT NULL DEFAULT FALSE"); log.info("auth_user notify_sms 列已添加"); } catch (Exception ex) { log.debug("notify_sms 列已存在: {}", ex.getMessage()); }
            try { jdbc.execute("ALTER TABLE auth_user ADD COLUMN notify_phone BOOLEAN NOT NULL DEFAULT FALSE"); log.info("auth_user notify_phone 列已添加"); } catch (Exception ex) { log.debug("notify_phone 列已存在: {}", ex.getMessage()); }
            try { jdbc.execute("ALTER TABLE auth_user ADD COLUMN email VARCHAR(255) UNIQUE"); log.info("auth_user email 列已添加"); } catch (Exception ex) { log.debug("email 列已存在: {}", ex.getMessage()); }
        }
    }

    private void ensureSmsCodeTable() {
        try {
            jdbc.execute("""
                CREATE TABLE auth_sms_code (
                    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                    phone       VARCHAR(20)  NOT NULL,
                    code        VARCHAR(6)   NOT NULL,
                    expire_at   DATETIME     NOT NULL,
                    used        BOOLEAN      NOT NULL DEFAULT FALSE,
                    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_sms_code_phone (phone)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            log.info("auth_sms_code 表已创建");
        } catch (Exception e) {
            log.debug("auth_sms_code 表已存在: {}", e.getMessage());
        }
    }

    private void ensureEmailCodeTable() {
        try {
            jdbc.execute("""
                CREATE TABLE auth_email_code (
                    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                    email       VARCHAR(255) NOT NULL,
                    code        VARCHAR(6)   NOT NULL,
                    expire_at   DATETIME     NOT NULL,
                    used        BOOLEAN      NOT NULL DEFAULT FALSE,
                    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_email_code_email (email)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            log.info("auth_email_code 表已创建");
        } catch (Exception e) {
            log.debug("auth_email_code 表已存在: {}", e.getMessage());
        }
    }

    private void ensureLoginCodeTable() {
        try {
            jdbc.execute("""
                CREATE TABLE login_code (
                    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                    code        VARCHAR(20)  NOT NULL UNIQUE,
                    role        VARCHAR(20)  NOT NULL,
                    used        BOOLEAN      NOT NULL DEFAULT FALSE,
                    user_id     BIGINT       DEFAULT NULL,
                    expires_at  DATETIME     NOT NULL,
                    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            log.info("login_code 表已创建");
        } catch (Exception e) {
            log.debug("login_code 表已存在: {}", e.getMessage());
        }
    }

    private void ensureAuditLogTable() {
        try {
            jdbc.execute("""
                CREATE TABLE audit_log (
                    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id     BIGINT,
                    action      VARCHAR(50)  NOT NULL,
                    target      VARCHAR(255),
                    detail      TEXT,
                    ip          VARCHAR(45),
                    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            log.info("audit_log 表已创建");
        } catch (Exception e) {
            log.debug("audit_log 表已存在: {}", e.getMessage());
        }
    }

    private void ensureUserNotificationLogTable() {
        try {
            jdbc.execute("""
                CREATE TABLE user_notification_log (
                    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id     BIGINT       NOT NULL,
                    channel     VARCHAR(16)  NOT NULL,
                    stock_code  VARCHAR(16),
                    type        VARCHAR(32)  NOT NULL,
                    title       VARCHAR(200) NOT NULL,
                    content     TEXT,
                    status      VARCHAR(16)  NOT NULL,
                    error       VARCHAR(500),
                    sent_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_unl_user_time (user_id, sent_at),
                    INDEX idx_unl_stock     (stock_code, sent_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            log.info("user_notification_log 表已创建");
        } catch (Exception e) {
            log.debug("user_notification_log 表已存在: {}", e.getMessage());
        }
    }

    // ── ADMIN 自举 ───────────────────────────────────────

    /** 无任何用户时自动创建首个 ADMIN（密码打印到日志） */
    @Transactional
    void bootstrapFirstAdmin() {
        // 已有任意用户，跳过创建
        if (userRepository.count() > 0) {
            // 无任何可用管理员 → 重置所有 admin 为启用（恢复入口）
            boolean hasEnabledAdmin = userRepository.findAll().stream()
                    .anyMatch(u -> u.getRole() == User.Role.ADMIN && !Boolean.TRUE.equals(u.getDisabled()));
            if (!hasEnabledAdmin) {
                log.warn("【安全恢复】未发现可用管理员，将重置现有 ADMIN 账号...");
                userRepository.findAll().stream()
                        .filter(u -> u.getRole() == User.Role.ADMIN)
                        .forEach(u -> { u.setDisabled(false); userRepository.save(u); });
                log.warn("【安全恢复】ADMIN 账号已恢复可用，请立即登录并检查安全设置。");
            }
            return;
        }

        String rawPassword = generateSecurePassword(16);
        User admin = new User();
        admin.setUsername("admin");
        admin.setRole(User.Role.ADMIN);
        admin.setPasswordHash(passwordEncoder.encode(rawPassword));
        userRepository.save(admin);

        log.warn("═══════════════════════════════════════════════════════");
        log.warn("【首次启动】系统已自动创建管理员账号：");
        log.warn("  用户名：admin");
        log.warn("  密码：{}", rawPassword);
        log.warn("请立即登录并修改密码！");
        log.warn("═══════════════════════════════════════════════════════");
    }

    private String generateSecurePassword(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                .replace("-", "").replace("_", "").substring(0, length);
    }

    /**
     * 热点股票池 — 龙头候选"入池"动作的落地表(v5 起独立于龙江投资股票池)。
     */
    private void ensureProsperityStockPoolTable() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS prosperity_stock_pool (
                    id INT PRIMARY KEY AUTO_INCREMENT,
                    stock_code VARCHAR(20) NOT NULL,
                    stock_name VARCHAR(50) DEFAULT NULL,
                    status VARCHAR(20) DEFAULT 'watching' COMMENT 'watching/hit_target/stopped/expired',
                    pool_count INT NOT NULL DEFAULT 1 COMMENT '累计入池次数',
                    first_added_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '首次入池时间',
                    last_added_at DATETIME DEFAULT NULL COMMENT '最近入池时间',
                    last_snap_date DATE DEFAULT NULL COMMENT '最近入池的快照日期',
                    sector_name VARCHAR(64) DEFAULT NULL,
                    combined_score DECIMAL(8,2) DEFAULT NULL,
                    latest_price DECIMAL(10,2) DEFAULT NULL,
                    buy_left_price DECIMAL(10,2) DEFAULT NULL,
                    sell_target_1 DECIMAL(10,2) DEFAULT NULL,
                    stop_loss_price DECIMAL(10,2) DEFAULT NULL,
                    core_position_pct DECIMAL(6,2) DEFAULT NULL,
                    tactical_position_pct DECIMAL(6,2) DEFAULT NULL,
                    action_signal VARCHAR(20) DEFAULT NULL,
                    memo TEXT COMMENT '入池理由(每次入池追加一条)',
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_pool_stock_code (stock_code),
                    KEY idx_pool_last_added (last_added_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                  COMMENT='热点选股股票池'
                """);
            log.info("prosperity_stock_pool 表已就绪");
        } catch (Exception e) {
            log.warn("检查 prosperity_stock_pool 表失败 (可忽略): {}", e.getMessage());
        }
    }

    private void ensurePickDailyMemoColumn() {
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'prosperity_pick_daily' AND column_name = 'memo'",
                Integer.class);
            if (count == null || count == 0) {
                jdbc.execute("ALTER TABLE prosperity_pick_daily ADD COLUMN memo TEXT DEFAULT NULL COMMENT '板块归属备注'");
                log.info("prosperity_pick_daily.memo 列已添加");
            }
        } catch (Exception e) {
            log.warn("检查 prosperity_pick_daily.memo 列失败 (可忽略): {}", e.getMessage());
        }
    }

    private void ensurePickDailyRevenueYoyMin3q() {
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'prosperity_pick_daily' AND column_name = 'revenue_yoy_min_3q'",
                Integer.class);
            if (count == null || count == 0) {
                jdbc.execute("ALTER TABLE prosperity_pick_daily ADD COLUMN revenue_yoy_min_3q DECIMAL(10,4) DEFAULT NULL COMMENT '近3季度营收同比最小值(%)'");
                log.info("prosperity_pick_daily.revenue_yoy_min_3q 列已添加");
            }
        } catch (Exception e) {
            log.warn("检查 prosperity_pick_daily.revenue_yoy_min_3q 列失败 (可忽略): {}", e.getMessage());
        }
    }

    private void ensurePipelineRunTable() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS prosperity_pipeline_run (
                    id INT PRIMARY KEY AUTO_INCREMENT,
                    snap_date DATE NOT NULL,
                    started_at DATETIME NOT NULL,
                    finished_at DATETIME DEFAULT NULL,
                    duration_ms BIGINT DEFAULT NULL,
                    status VARCHAR(20) NOT NULL,
                    message VARCHAR(256) DEFAULT NULL,
                    provider VARCHAR(32) DEFAULT NULL,
                    sector_count INT DEFAULT NULL,
                    leader_count INT DEFAULT NULL,
                    hard_filtered_count INT DEFAULT NULL,
                    candidate_count INT DEFAULT NULL,
                    INDEX idx_pipeline_snap (snap_date),
                    INDEX idx_pipeline_started (started_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            log.info("prosperity_pipeline_run 表已就绪");
        } catch (Exception e) {
            log.warn("检查 prosperity_pipeline_run 表失败 (可忽略): {}", e.getMessage());
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

    private void ensureXieboInvestTables() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS invest_xiebo_watchlist (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    stock_code VARCHAR(20) NOT NULL UNIQUE,
                    stock_name VARCHAR(64) NOT NULL,
                    display_order INT DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_xiebo_watchlist_order (display_order, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS invest_xiebo_analysis_record (
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
                    INDEX idx_xiebo_analysis_stock_date (stock_code, analysis_date),
                    INDEX idx_xiebo_analysis_status (status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            log.info("xiebo invest tables are ready");
        } catch (Exception e) {
            log.warn("检查 xiebo invest 表失败 (可忽略): {}", e.getMessage());
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
            {"pool_update_error", "VARCHAR(1000)", "股票池数据刷新错误"},
            // 2026-06-30 数据库漂移补齐：sql/wucai_trade.sql 有这列但实际库缺。
            // entity InvestStockPool.targetSellPrice 期望它存在，缺失时 JPA 报
            // "Unknown column 'isp1_0.target_sell_price'" 导致 /api/invest/pool 500。
            {"target_sell_price", "DECIMAL(10,2)", "希望卖出价"}
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

    /**
     * 2026-06-30 schema drift 兜底：invest_stock_pool.pool_type 历史 ENUM 是
     * ('quality','tech_vc','tech_ai','potential')，但前端已有 innovative_drug 池
     * （见 invest_pool_meta），InvestService.addToPool / poolTypeLabelOf 都按
     * 'innovative_drug' 处理。canonical sql/wucai_trade.sql 也漏写了。
     * 这里 idempotent 地把 ENUM 扩到包含 'innovative_drug'。
     */
    private void ensureInvestStockPoolEnum() {
        try {
            String colType = jdbc.queryForObject("""
                SELECT COLUMN_TYPE FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'invest_stock_pool'
                  AND column_name = 'pool_type'
                """, String.class);
            if (colType == null) return;
            if (colType.contains("'innovative_drug'")) {
                log.debug("invest_stock_pool.pool_type 已包含 innovative_drug");
                return;
            }
            jdbc.execute("""
                ALTER TABLE invest_stock_pool
                  MODIFY COLUMN pool_type
                  ENUM('quality','tech_vc','tech_ai','potential','innovative_drug')
                  NOT NULL COMMENT '质量优选/科技风投/科技AI/潜力监控/创新药'
                """);
            log.info("invest_stock_pool.pool_type ENUM 已扩至含 innovative_drug");
        } catch (Exception e) {
            log.warn("检查 invest_stock_pool.pool_type ENUM 失败 (可忽略): {}", e.getMessage());
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

    /**
     * prosperity_leader_candidate 财务硬筛字段(Step3 输出):营收同比/扣非同比/毛利率/资产负债率/经营现金流/ROE
     * 实体类 ProsperityLeaderCandidate 在筛选链中写入这些字段,但 SQL 脚本里没有建,运行时 SELECT 会报
     * Unknown column 错。这里保证线上表结构与实体一致。
     */
    private void ensureProsperityLeaderFinanceColumns() {
        String[][] columns = {
            {"revenue_yoy_min_4q",         "DECIMAL(10,4)",  "近4季营收同比最小值(%)"},
            {"deducted_netprofit_yoy_min_4q","DECIMAL(10,4)",  "近4季扣非同比最小值(%)"},
            {"gross_margin_avg_4q",        "DECIMAL(10,4)",  "近4季毛利率均值(%)"},
            {"debt_ratio_latest",          "DECIMAL(10,4)",  "最新资产负债率(%)"},
            {"operating_cashflow_sum_4q",  "DECIMAL(20,2)",  "近4季经营现金流合计(元)"},
            {"roe_latest",                 "DECIMAL(10,4)",  "最新 ROE(%)"}
        };
        for (String[] col : columns) {
            try {
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'prosperity_leader_candidate' AND column_name = '" + col[0] + "'",
                        Integer.class);
                if (count == null || count == 0) {
                    jdbc.execute("ALTER TABLE prosperity_leader_candidate ADD COLUMN " + col[0] + " " + col[1] + " DEFAULT NULL COMMENT '" + col[2] + "'");
                    log.info("prosperity_leader_candidate.{} 列已添加", col[0]);
                }
            } catch (Exception e) {
                log.warn("检查 prosperity_leader_candidate.{} 列失败 (可忽略): {}", col[0], e.getMessage());
            }
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

    /**
     * 给 prosperity_stock_pool 加 owner_id 字段（个人池隔离）。
     * 唯一约束从 (stock_code) 单列改为 (owner_id, stock_code) 组合。
     */
    private void ensureProsperityStockPoolOwnerId() {
        try {
            // 加字段（幂等）
            jdbc.execute("ALTER TABLE prosperity_stock_pool ADD COLUMN owner_id BIGINT DEFAULT NULL");
            log.info("prosperity_stock_pool.owner_id 字段已添加");
        } catch (Exception e) {
            log.info("prosperity_stock_pool.owner_id 字段已存在或跳过: " + e.getMessage());
        }
        try {
            // 删旧唯一约束，加新的组合唯一（幂等）
            jdbc.execute("ALTER TABLE prosperity_stock_pool DROP INDEX uk_stock_code");
            log.info("prosperity_stock_pool 旧 uk_stock_code 已删除");
        } catch (Exception e) {
            log.info("prosperity_stock_pool 旧唯一约束不存在或跳过: " + e.getMessage());
        }
        try {
            jdbc.execute("ALTER TABLE prosperity_stock_pool ADD UNIQUE INDEX uk_owner_stock (owner_id, stock_code)");
            log.info("prosperity_stock_pool uk_owner_stock 组合唯一已添加");
        } catch (Exception e) {
            log.info("prosperity_stock_pool uk_owner_stock 已存在或跳过: " + e.getMessage());
        }
    }

    // ── 每日统计表 ────────────────────────────────────────

    private void ensurePageViewStatTable() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS page_view_stat (
                    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
                    user_id         BIGINT        DEFAULT NULL COMMENT '用户ID，null=游客',
                    page_path       VARCHAR(255)  NOT NULL COMMENT '页面路径',
                    visit_time      DATETIME      NOT NULL COMMENT '访问时间',
                    visit_date      DATE          NOT NULL COMMENT '访问日期（冗余）',
                    duration_seconds INT           DEFAULT NULL COMMENT '该页停留时长（秒）',
                    user_agent      VARCHAR(512)  DEFAULT NULL COMMENT '浏览器UA摘要',
                    session_id      VARCHAR(64)   DEFAULT NULL COMMENT '会话ID',
                    INDEX idx_pvs_user_date (user_id, visit_date),
                    INDEX idx_pvs_date (visit_date),
                    INDEX idx_pvs_page (page_path)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            log.info("page_view_stat 表已就绪");
        } catch (Exception e) {
            log.debug("page_view_stat 表已存在: {}", e.getMessage());
        }
    }

    private void ensureUserDailyStatTable() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_daily_stat (
                    id                    BIGINT PRIMARY KEY AUTO_INCREMENT,
                    user_id               BIGINT     DEFAULT NULL COMMENT '用户ID，null=游客',
                    stat_date             DATE       NOT NULL COMMENT '统计日期',
                    page_view_count       INT        NOT NULL DEFAULT 0 COMMENT '当日PV',
                    unique_pages          INT        NOT NULL DEFAULT 0 COMMENT '当日访问页面种类数',
                    total_duration_seconds INT        NOT NULL DEFAULT 0 COMMENT '当日总停留时长（秒）',
                    first_visit_time      DATETIME   DEFAULT NULL,
                    last_visit_time       DATETIME   DEFAULT NULL,
                    login_count           INT        NOT NULL DEFAULT 0 COMMENT '当日登录次数',
                    is_new_user           TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否当日注册',
                    UNIQUE KEY uk_uds_user_date (user_id, stat_date),
                    INDEX idx_uds_date (stat_date),
                    INDEX idx_uds_user (user_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            log.info("user_daily_stat 表已就绪");
        } catch (Exception e) {
            log.debug("user_daily_stat 表已存在: {}", e.getMessage());
        }
    }

    /**
     * 股票池元信息表（封面图、估值方法、周度机会）。每种 pool_type 一行。
     */
    private void ensureInvestPoolMetaTable() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS invest_pool_meta (
                    pool_type              VARCHAR(20)  NOT NULL,
                    display_name           VARCHAR(64)  NOT NULL,
                    cover_image_url        VARCHAR(512) DEFAULT NULL,
                    valuation_method_md    LONGTEXT     DEFAULT NULL,
                    valuation_method_html  LONGTEXT     DEFAULT NULL,
                    weekly_opportunity_md  LONGTEXT     DEFAULT NULL,
                    weekly_opportunity_html LONGTEXT    DEFAULT NULL,
                    display_order          INT          NOT NULL DEFAULT 0,
                    created_at             DATETIME     DEFAULT CURRENT_TIMESTAMP,
                    updated_at             DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (pool_type)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                  COMMENT='股票池类型元信息'
                """);
            log.info("invest_pool_meta 表已就绪");
        } catch (Exception e) {
            log.warn("检查 invest_pool_meta 表失败 (可忽略): {}", e.getMessage());
        }
    }

    /**
     * 3 种股票池元信息种子（占位文案，ADMIN 可在页面里编辑）。
     * 创新药、质量优选首批不放示例股票，由用户通过加入股票池入口手动添加。
     */
    private void ensureInvestPoolMetaSeed() {
        try {
            jdbc.update("""
                INSERT INTO invest_pool_meta
                  (pool_type, display_name, cover_image_url, valuation_method_md, weekly_opportunity_md, display_order)
                VALUES
                  ('tech_vc', '科技AI', 'images/pool-covers/tech-ai.png', '### 10 倍 PS 市值法\n\n合理市值 = 预测营收 × 10\n\n- 当前市值 ≤ Y1 × 10：低估\n- 当前市值介于 Y1×10 ~ Y2×10：合理\n- 当前市值 ≥ Y2 × 10：泡沫\n\n适用于净利率接近 25% 的高科技成长股。', '本周暂无更新', 1),
                  ('innovative_drug', '创新药', 'images/pool-covers/innovative_drug.svg', '### 创新药估值方法\n\n按 III 期管线 NPV 加总。\n\n待补充：\n- 风险调整成功率 (POS)\n- 上市峰值销售 (Peak Sales)\n- 净利率假设\n- 折现率与管线分摊', '本周暂无更新', 2),
                  ('quality', '质量优选', 'images/pool-covers/quality.svg', '### 质量优选 · 巴菲特式估值\n\n**核心**：自由现金流优异、赚取真金白银、分红稳定。\n\n**简易模型**：现金流折现 + PE 匹配法\n\n合理 PE ≈ 预期未来 10 年净利润复合增长率 × 2\n\n**判断口诀**：若股票 PE 为 30 倍，需确认其未来十年能否实现 15% 复合增长。达标则考虑，不达标则放弃。\n\n代表企业：片仔癀、海天味业。', '本周暂无更新', 3)
                ON DUPLICATE KEY UPDATE display_name = VALUES(display_name)
                """);
            log.info("invest_pool_meta 3 行种子数据已就绪");
        } catch (Exception e) {
            log.warn("插入 invest_pool_meta 种子失败 (可忽略): {}", e.getMessage());
        }
    }

    /**
     * 每周机会点 3×3 卡片槽位表（每个 pool_type 固定 9 个 slot）。
     * 无 seed：首次启动为空表，ADMIN 在页面里编辑填充。
     */
    private void ensureWeeklyOpportunitySlotTable() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS invest_weekly_opportunity_slot (
                    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                    pool_type       VARCHAR(20)  NOT NULL,
                    slot_index      INT          NOT NULL,
                    stock_code      VARCHAR(16)  DEFAULT NULL,
                    user_stock_name VARCHAR(100) DEFAULT NULL,
                    reason          VARCHAR(500) DEFAULT NULL,
                    image_url       VARCHAR(500) DEFAULT NULL,
                    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
                    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_pool_slot (pool_type, slot_index),
                    KEY idx_pool_type (pool_type)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                  COMMENT='每周机会点 3×3 卡片槽位'
                """);
            // 兼容老库：补 user_stock_name / image_url 列
            try { jdbc.execute("ALTER TABLE invest_weekly_opportunity_slot ADD COLUMN user_stock_name VARCHAR(100) DEFAULT NULL COMMENT '用户手工填的股票名（代码不在池中时兜底）'"); log.info("invest_weekly_opportunity_slot.user_stock_name 列已添加"); } catch (Exception ex) { log.debug("user_stock_name 列已存在: {}", ex.getMessage()); }
            try { jdbc.execute("ALTER TABLE invest_weekly_opportunity_slot ADD COLUMN image_url VARCHAR(500) DEFAULT NULL COMMENT 'slot 参考截图 URL'"); log.info("invest_weekly_opportunity_slot.image_url 列已添加"); } catch (Exception ex) { log.debug("image_url 列已存在: {}", ex.getMessage()); }
            log.info("invest_weekly_opportunity_slot 表已就绪");
        } catch (Exception e) {
            log.warn("检查 invest_weekly_opportunity_slot 表失败 (可忽略): {}", e.getMessage());
        }
    }

    /**
     * 谢博投资 · 每周重点股票 3×3 卡片槽位表。
     * pool_type: watch / focus / explore 三分类。
     */
    private void ensureXieboWeeklyOpportunitySlotTable() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS xiebo_weekly_opportunity_slot (
                    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
                    pool_type  VARCHAR(20)  NOT NULL,
                    slot_index INT          NOT NULL,
                    stock_code VARCHAR(16)  DEFAULT NULL,
                    reason     VARCHAR(500) DEFAULT NULL,
                    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_xiebo_pool_slot (pool_type, slot_index),
                    KEY idx_xiebo_pool_type (pool_type)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                  COMMENT='谢博投资 每周重点股票 3×3 卡片槽位'
                """);
            log.info("xiebo_weekly_opportunity_slot 表已就绪");
        } catch (Exception e) {
            log.warn("检查 xiebo_weekly_opportunity_slot 表失败 (可忽略): {}", e.getMessage());
        }
    }

    // ── 三池持仓状态聚合表（invest_position_common）──────────
    // 将 invest_stock_pool / tech_ai_pool / potential_pool 的持仓/告警字段
    // 迁移到统一表，消除字段冗余。一次性迁移后各池实体改为 @OneToOne 引用。
    //
    // 复合主键 (pool_type, stock_code) 确保同一股票可出现在不同池中。
    // 持仓流水（成交记录）暂存各池独立的 *_position_fill 表，待后续统一。
    private void ensureInvestPositionCommon() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS invest_position_common (
                    stock_code                  VARCHAR(20)  NOT NULL,
                    pool_type                   VARCHAR(20)  NOT NULL,
                    status                      VARCHAR(10)  DEFAULT 'watching',
                    alert_state                 VARCHAR(20)  DEFAULT 'none',
                    last_alert_at               DATETIME     DEFAULT NULL,
                    alert_minute_1m_pct         DECIMAL(8,2) DEFAULT NULL,
                    alert_minute_5m_pct         DECIMAL(8,2) DEFAULT NULL,
                    alert_daily_pct             DECIMAL(8,2) DEFAULT NULL,
                    alert_three_day_pct         DECIMAL(8,2) DEFAULT NULL,
                    alert_turnover_ratio_pct    DECIMAL(8,2) DEFAULT NULL,
                    entry_price                 DECIMAL(10,2) DEFAULT NULL,
                    position_lots               DECIMAL(10,2) DEFAULT 0.00,
                    avg_cost                    DECIMAL(10,2) DEFAULT NULL,
                    total_invested              DECIMAL(14,2) DEFAULT NULL,
                    add_count                   INT           DEFAULT 0,
                    last_add_price              DECIMAL(10,2) DEFAULT NULL,
                    peak_price                  DECIMAL(10,2) DEFAULT NULL,
                    stop_price                  DECIMAL(10,2) DEFAULT NULL,
                    realized_pnl                DECIMAL(14,2) DEFAULT 0.00,
                    position_state              VARCHAR(20)  DEFAULT 'none',
                    take_profit_done            TINYINT(1)   DEFAULT 0,
                    opened_at                   DATETIME     DEFAULT NULL,
                    target_sell_price            DECIMAL(10,2) DEFAULT NULL,
                    add_step_pct                DECIMAL(6,2)  DEFAULT NULL,
                    trail_pct                   DECIMAL(6,2)  DEFAULT NULL,
                    add_size_schedule           VARCHAR(50)  DEFAULT NULL,
                    max_lots                    DECIMAL(10,2) DEFAULT NULL,
                    take_profit_pct             DECIMAL(6,2)  DEFAULT NULL,
                    breakeven_after_tp          TINYINT(1)   DEFAULT 1,
                    time_stop_days              INT           DEFAULT NULL,
                    use_atr                     TINYINT(1)   DEFAULT 0,
                    atr_period                  INT           DEFAULT NULL,
                    atr_add_mult                DECIMAL(6,2)  DEFAULT NULL,
                    atr_trail_mult              DECIMAL(6,2)  DEFAULT NULL,
                    created_at                  DATETIME     DEFAULT CURRENT_TIMESTAMP,
                    updated_at                  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (pool_type, stock_code),
                    KEY idx_ipc_stock_code (stock_code)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                  COMMENT='三池持仓/告警状态聚合表（invest/tech_ai/potential 共用）'
                """);
            log.info("invest_position_common 表已就绪");
        } catch (Exception e) {
            log.warn("invest_position_common 建表失败 (可忽略): {}", e.getMessage());
            return;
        }

        // 迁移 invest_stock_pool 持仓字段（pool_type = 'invest'）
        migratePoolToCommon("invest_stock_pool", "invest");
        // 迁移 tech_ai_pool 持仓字段（pool_type = 'tech_ai'）
        migratePoolToCommon("tech_ai_pool", "tech_ai");
        // 迁移 potential_pool 持仓字段（pool_type = 'potential'）
        migratePoolToCommon("potential_pool", "potential");

        // 删除 invest_stock_pool 冗余列（26 个持仓 + 告警字段，已迁至 invest_position_common）
        dropPositionColumnsFromPool("invest_stock_pool", new String[]{
            "status", "alert_state", "last_alert_at",
            "alert_minute_1m_pct", "alert_minute_5m_pct", "alert_daily_pct",
            "alert_three_day_pct", "alert_turnover_ratio_pct",
            "entry_price", "position_lots", "avg_cost", "total_invested",
            "add_count", "last_add_price", "peak_price", "stop_price",
            "realized_pnl", "position_state", "take_profit_done", "opened_at",
            "add_step_pct", "trail_pct", "add_size_schedule", "max_lots",
            "take_profit_pct", "breakeven_after_tp", "time_stop_days",
            "use_atr", "atr_period", "atr_add_mult", "atr_trail_mult",
            "target_sell_price"
        });

        // 删除 tech_ai_pool 冗余列（26 个持仓 + 告警字段，已迁至 invest_position_common）
        dropPositionColumnsFromPool("tech_ai_pool", new String[]{
            "status", "alert_state", "last_alert_at",
            "alert_minute_1m_pct", "alert_minute_5m_pct", "alert_daily_pct",
            "alert_three_day_pct", "alert_turnover_ratio_pct",
            "entry_price", "position_lots", "avg_cost", "total_invested",
            "add_count", "last_add_price", "peak_price", "stop_price",
            "realized_pnl", "position_state", "take_profit_done", "opened_at",
            "target_sell_price", "add_step_pct", "trail_pct",
            "add_size_schedule", "max_lots", "take_profit_pct",
            "breakeven_after_tp", "time_stop_days",
            "use_atr", "atr_period", "atr_add_mult", "atr_trail_mult"
        });

        // 删除 potential_pool 冗余列（26 个持仓 + 告警字段，已迁至 invest_position_common）
        dropPositionColumnsFromPool("potential_pool", new String[]{
            "status",
            "alert_minute_1m_pct", "alert_minute_5m_pct", "alert_daily_pct",
            "alert_three_day_pct", "alert_turnover_ratio_pct",
            "entry_price", "position_lots", "avg_cost", "total_invested",
            "add_count", "last_add_price", "peak_price", "stop_price",
            "realized_pnl", "position_state", "take_profit_done", "opened_at",
            "target_sell_price", "add_step_pct", "trail_pct",
            "add_size_schedule", "max_lots", "take_profit_pct",
            "breakeven_after_tp", "time_stop_days",
            "use_atr", "atr_period", "atr_add_mult", "atr_trail_mult"
        });
    }

    private void migratePoolToCommon(String sourceTable, String poolType) {
        String sql = String.format("""
            INSERT INTO invest_position_common (
                stock_code, pool_type, status, alert_state, last_alert_at,
                alert_minute_1m_pct, alert_minute_5m_pct, alert_daily_pct,
                alert_three_day_pct, alert_turnover_ratio_pct,
                entry_price, position_lots, avg_cost, total_invested,
                add_count, last_add_price, peak_price, stop_price,
                realized_pnl, position_state, take_profit_done, opened_at,
                target_sell_price, add_step_pct, trail_pct,
                add_size_schedule, max_lots, take_profit_pct,
                breakeven_after_tp, time_stop_days,
                use_atr, atr_period, atr_add_mult, atr_trail_mult
            )
            SELECT
                stock_code, '%s', status, alert_state, last_alert_at,
                alert_minute_1m_pct, alert_minute_5m_pct, alert_daily_pct,
                alert_three_day_pct, alert_turnover_ratio_pct,
                entry_price, position_lots, avg_cost, total_invested,
                add_count, last_add_price, peak_price, stop_price,
                realized_pnl, position_state, take_profit_done, opened_at,
                target_sell_price, add_step_pct, trail_pct,
                add_size_schedule, max_lots, take_profit_pct,
                breakeven_after_tp, time_stop_days,
                use_atr, atr_period, atr_add_mult, atr_trail_mult
            FROM %s
            ON DUPLICATE KEY UPDATE
                status = VALUES(status),
                alert_state = VALUES(alert_state),
                last_alert_at = VALUES(last_alert_at),
                alert_minute_1m_pct = VALUES(alert_minute_1m_pct),
                alert_minute_5m_pct = VALUES(alert_minute_5m_pct),
                alert_daily_pct = VALUES(alert_daily_pct),
                alert_three_day_pct = VALUES(alert_three_day_pct),
                alert_turnover_ratio_pct = VALUES(alert_turnover_ratio_pct),
                entry_price = VALUES(entry_price),
                position_lots = VALUES(position_lots),
                avg_cost = VALUES(avg_cost),
                total_invested = VALUES(total_invested),
                add_count = VALUES(add_count),
                last_add_price = VALUES(last_add_price),
                peak_price = VALUES(peak_price),
                stop_price = VALUES(stop_price),
                realized_pnl = VALUES(realized_pnl),
                position_state = VALUES(position_state),
                take_profit_done = VALUES(take_profit_done),
                opened_at = VALUES(opened_at),
                target_sell_price = VALUES(target_sell_price),
                add_step_pct = VALUES(add_step_pct),
                trail_pct = VALUES(trail_pct),
                add_size_schedule = VALUES(add_size_schedule),
                max_lots = VALUES(max_lots),
                take_profit_pct = VALUES(take_profit_pct),
                breakeven_after_tp = VALUES(breakeven_after_tp),
                time_stop_days = VALUES(time_stop_days),
                use_atr = VALUES(use_atr),
                atr_period = VALUES(atr_period),
                atr_add_mult = VALUES(atr_add_mult),
                atr_trail_mult = VALUES(atr_trail_mult)
            """, poolType, sourceTable);
        try {
            jdbc.execute(sql);
            log.info("invest_position_common 数据迁移完成（来源：{}）", sourceTable);
        } catch (Exception e) {
            log.warn("migratePoolToCommon({}) 失败 (可忽略): {}", sourceTable, e.getMessage());
        }
    }

    private void dropPositionColumnsFromPool(String tableName, String[] columns) {
        for (String col : columns) {
            try {
                Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = '" + tableName + "' AND column_name = '" + col + "'",
                    Integer.class);
                if (count != null && count > 0) {
                    jdbc.execute("ALTER TABLE " + tableName + " DROP COLUMN " + col);
                    log.info("{}.{} 列已删除（已迁至 invest_position_common）", tableName, col);
                }
            } catch (Exception e) {
                log.debug("{}.{} 列删除跳过: {}", tableName, col, e.getMessage());
            }
        }
    }

    /**
     * Monitor Fusion (2026-06-30) — 给 invest_position_common 加 9 列：
     * 固定买入/卖出价 + ATR 振幅 + %-based 止损 + Server酱模板。
     * 每个新列默认禁用（固定列可空，启用列默认 0），不影响现有行行为。
     */
    // ── 投资金句表 ───────────────────────────────────────

    private void ensureInvestQuoteTable() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS invest_quote (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    content TEXT NOT NULL,
                    author VARCHAR(100) DEFAULT NULL,
                    source VARCHAR(200) DEFAULT NULL,
                    tags VARCHAR(500) DEFAULT NULL,
                    likes INT NOT NULL DEFAULT 0,
                    imported_node_id BIGINT DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_invest_quote_created (created_at),
                    INDEX idx_invest_quote_author (author)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            log.info("invest_quote table ready");
        } catch (Exception e) {
            log.warn("检查 invest_quote 表失败 (可忽略): {}", e.getMessage());
        }
    }

    // ── 交易日志表 ───────────────────────────────────────

    private void ensureJournalTables() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS journal_trade ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "mode VARCHAR(10) NOT NULL,"
                    + "stock_code VARCHAR(20) NOT NULL,"
                    + "stock_name VARCHAR(50),"
                    + "entry_price DECIMAL(10,2) NOT NULL,"
                    + "entry_date DATETIME NOT NULL,"
                    + "entry_shares INT NOT NULL,"
                    + "account_at_entry DECIMAL(14,2),"
                    + "risk_percent DECIMAL(5,4),"
                    + "stop_price DECIMAL(10,2) NOT NULL,"
                    + "target_price DECIMAL(10,2),"
                    + "exit_price DECIMAL(10,2),"
                    + "exit_date DATETIME,"
                    + "exit_reason VARCHAR(30),"
                    + "initial_risk DECIMAL(10,2) NOT NULL,"
                    + "pnl_amount DECIMAL(14,2),"
                    + "r_multiple DECIMAL(8,4),"
                    + "is_open TINYINT DEFAULT 1,"
                    + "tags VARCHAR(200),"
                    + "setup_notes TEXT,"
                    + "review_notes TEXT,"
                    + "source VARCHAR(20),"
                    + "source_ref_id BIGINT,"
                    + "is_deleted TINYINT DEFAULT 0,"
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "INDEX idx_mode_open (mode, is_open),"
                    + "INDEX idx_stock (stock_code),"
                    + "INDEX idx_exit_date (exit_date),"
                    + "UNIQUE KEY uk_source_ref (source, source_ref_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            log.info("journal_trade 表已就绪");
        } catch (Exception e) {
            log.warn("检查 journal_trade 表失败 (可忽略): {}", e.getMessage());
        }
        try {
            jdbc.execute("ALTER TABLE journal_trade ADD COLUMN IF NOT EXISTS created_by VARCHAR(50)");
        } catch (Exception e) {
            log.warn("添加 journal_trade.created_by 列失败 (可忽略): {}", e.getMessage());
        }
    }

    private void ensureMonitorFusionColumns() {
        String[][] columns = {
            {"monitor_mode",         "VARCHAR(20) NOT NULL DEFAULT 'standard'",                    "三态模式 standard|atr_strict|fixed_only"},
            {"fixed_buy_price",      "DECIMAL(10,2) DEFAULT NULL",                                "固定买入价"},
            {"fixed_sell_price",     "DECIMAL(10,2) DEFAULT NULL",                                "固定卖出价"},
            {"fixed_buy_enabled",    "TINYINT(1) NOT NULL DEFAULT 0",                              "启用固定买入触发"},
            {"fixed_sell_enabled",   "TINYINT(1) NOT NULL DEFAULT 0",                              "启用固定卖出触发"},
            {"atr_alert_amplitude",  "DECIMAL(8,3) DEFAULT NULL",                                 "ATR 振幅倍数(例如 1.500 表示 1.5x ATR)"},
            {"atr_alert_enabled",    "TINYINT(1) NOT NULL DEFAULT 0",                              "启用 ATR 振幅触发"},
            {"stop_loss_pct",        "DECIMAL(8,2) DEFAULT NULL",                                  "%-based 止损(存负数, -8.00 = -8%)"},
            {"serverchan_template",  "VARCHAR(50) NOT NULL DEFAULT 'standard'",                   "Server酱模板 standard|compact|verbose"}
        };
        for (String[] col : columns) {
            try {
                Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'invest_position_common' AND column_name = '" + col[0] + "'",
                    Integer.class);
                if (count == null || count == 0) {
                    jdbc.execute("ALTER TABLE invest_position_common ADD COLUMN " + col[0] + " " + col[1] + " COMMENT '" + col[2] + "'");
                    log.info("invest_position_common.{} 列已添加", col[0]);
                }
            } catch (Exception e) {
                log.warn("检查 invest_position_common.{} 列失败 (可忽略): {}", col[0], e.getMessage());
            }
        }
    }
}
