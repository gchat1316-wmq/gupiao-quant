-- V18: wish_pool (许愿池留言表)
-- Source: SchemaInitializer.ensureWishPoolTable() / sql/wish_pool_init.sql
-- 列名 display_flag 与 Java 字段 displayFlag 对应(避开 DDL 上 display 关键字歧义)

CREATE TABLE IF NOT EXISTS wish_pool (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    wish         VARCHAR(500) NOT NULL,
    page         VARCHAR(120) DEFAULT NULL,
    email        VARCHAR(120) DEFAULT NULL,
    ip           VARCHAR(45)  DEFAULT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/REPLIED/ARCHIVED',
    reply        TEXT         DEFAULT NULL,
    reply_by     VARCHAR(50)  DEFAULT NULL,
    reply_at     DATETIME     DEFAULT NULL,
    display_flag TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '右下角公开轮播开关',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_wp_status_created (status, created_at),
    INDEX idx_wp_display_reply_at (display_flag, reply_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='许愿池留言';
