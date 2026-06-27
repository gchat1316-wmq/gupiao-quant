-- ============================================================
-- gupiao-quant 权限体系建表 SQL
-- ============================================================

-- 用户表
CREATE TABLE IF NOT EXISTS `auth_user` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `phone` VARCHAR(20) UNIQUE COMMENT '手机号',
  `password_hash` VARCHAR(255) COMMENT '密码哈希（短信登录用户可为空）',
  `openid` VARCHAR(100) UNIQUE COMMENT '微信 openid',
  `unionid` VARCHAR(100) UNIQUE COMMENT '微信 unionid',
  `username` VARCHAR(50) COMMENT '展示名',
  `role` ENUM('admin','trader','viewer') NOT NULL DEFAULT 'trader',
  `disabled` TINYINT NOT NULL DEFAULT 0,
  `last_login_at` DATETIME,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_phone` (`phone`),
  INDEX `idx_openid` (`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 验证码表
CREATE TABLE IF NOT EXISTS `auth_sms_code` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `phone` VARCHAR(20) NOT NULL,
  `code` VARCHAR(6) NOT NULL,
  `expire_at` DATETIME NOT NULL,
  `used` TINYINT NOT NULL DEFAULT 0,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_phone_code` (`phone`, `code`, `used`),
  INDEX `idx_expire` (`expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='短信验证码';

-- 个人股票池（每个用户独立的热点选股池）
CREATE TABLE IF NOT EXISTS `auth_user_pool` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `user_id` BIGINT NOT NULL,
  `pool_name` VARCHAR(100) NOT NULL DEFAULT '我的股票池',
  `stocks` JSON COMMENT '[{code,name,note,addedAt}]',
  `is_public` TINYINT NOT NULL DEFAULT 0,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人股票池';

-- 操作审计日志
CREATE TABLE IF NOT EXISTS `auth_audit_log` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `user_id` BIGINT,
  `action` VARCHAR(50) NOT NULL,
  `target` VARCHAR(100),
  `detail` JSON,
  `ip` VARCHAR(50),
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_user_id` (`user_id`),
  INDEX `idx_action` (`action`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志';

-- ============================================================
-- 初始化 admin 账号（手机号 13800138000，密码 admin123）
-- 密码原文: admin123  →  bcrypt hash:
-- $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n3.sSLQoQbJMxxH5QMH.y
-- ============================================================
INSERT IGNORE INTO `auth_user` (`phone`, `password_hash`, `username`, `role`)
VALUES ('13800138000', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n3.sSLQoQbJMxxH5QMH.y', '管理员', 'admin');
