-- V11: stats tables (page_view_stat + user_daily_stat)
-- Source: SchemaInitializer.ensurePageViewStatTable() + ensureUserDailyStatTable()

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
