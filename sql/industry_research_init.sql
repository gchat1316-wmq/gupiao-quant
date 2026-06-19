-- ============================================================
-- 产业投研 (industry_research)
-- 建表脚本：建库后请执行本脚本
-- ============================================================

-- 1) 产业目录（左侧菜单）
CREATE TABLE IF NOT EXISTS industry_research_category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(50) NOT NULL UNIQUE COMMENT '产业 code：ai-compute / semiconductor / new-energy / ...',
    name VARCHAR(100) NOT NULL COMMENT '显示名：AI 算力产业链 / 半导体 / 新能源 ...',
    icon VARCHAR(50) DEFAULT NULL COMMENT 'emoji 或图标 key',
    parent_id BIGINT DEFAULT NULL COMMENT '支持二级目录（可空）',
    sort_order INT NOT NULL DEFAULT 0,
    enabled TINYINT NOT NULL DEFAULT 1,
    description VARCHAR(500) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ir_cat_parent (parent_id),
    INDEX idx_ir_cat_sort (sort_order, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='产业目录';

-- 2) 文章主表
CREATE TABLE IF NOT EXISTS industry_research_article (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category_id BIGINT NOT NULL,
    slug VARCHAR(80) NOT NULL UNIQUE COMMENT 'URL 友好 slug',
    title VARCHAR(200) NOT NULL COMMENT '如：AI 算力产业链深度分析',
    subtitle VARCHAR(500) DEFAULT NULL COMMENT '副标题 / 摘要',
    status VARCHAR(20) NOT NULL DEFAULT 'draft' COMMENT 'draft / published / archived',
    version INT NOT NULL DEFAULT 1,
    update_date DATE DEFAULT NULL COMMENT '数据时点（视频演示用 2024-05-27）',
    source_summary VARCHAR(500) DEFAULT NULL COMMENT '数据来源说明：1171 条研报 + LightCounting + ...',
    cover_image VARCHAR(500) DEFAULT NULL,
    tags VARCHAR(500) DEFAULT NULL COMMENT '逗号分隔',
    view_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ir_article_cat (category_id, status),
    INDEX idx_ir_article_slug (slug)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='产业投研文章主表';

-- 3) 文章 Tab/章节（11 个 Tab 结构化存储）
-- content_json 存储该 Tab 的结构化数据：metrics / bom / table / text / stock_card / conclusion / chart
CREATE TABLE IF NOT EXISTS industry_research_section (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    article_id BIGINT NOT NULL,
    section_key VARCHAR(50) NOT NULL COMMENT 'overview / optical / pcb / hbm / cpu-gpu / downstream / energy / space / core-stocks / valuation / news',
    section_title VARCHAR(100) NOT NULL COMMENT '如：光模块 / PCB/HDI ...',
    section_order INT NOT NULL DEFAULT 0,
    content_type VARCHAR(20) NOT NULL DEFAULT 'mixed' COMMENT 'metrics / bom / table / text / stock_card / conclusion / chart / mixed',
    content_json LONGTEXT NOT NULL COMMENT '结构化 JSON 内容',
    source VARCHAR(500) DEFAULT NULL COMMENT '该 Tab 的数据来源',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ir_section (article_id, section_key),
    INDEX idx_ir_section_article (article_id, section_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章章节（每个 Tab 一行）';

-- 4) 投研任务（A-Stock-Data + Kimi CLI + News Radar 流水线）
CREATE TABLE IF NOT EXISTS industry_research_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category_id BIGINT NOT NULL,
    article_id BIGINT DEFAULT NULL COMMENT '完成后写回 article_id',
    task_name VARCHAR(200) NOT NULL,
    keyword VARCHAR(500) DEFAULT NULL COMMENT '研报检索关键词',
    status VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending / running / success / failed',
    stage VARCHAR(30) NOT NULL DEFAULT 'init' COMMENT 'init / data-fetch / report-read / news-radar / assembling / done',
    progress INT NOT NULL DEFAULT 0 COMMENT '0-100',
    total_reports INT DEFAULT NULL COMMENT 'Kimi 读了几篇',
    news_count INT DEFAULT NULL COMMENT 'News Radar 抓到几条',
    error_message TEXT DEFAULT NULL,
    log TEXT DEFAULT NULL COMMENT '执行日志（可滚动追加）',
    started_at DATETIME DEFAULT NULL,
    finished_at DATETIME DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ir_task_cat (category_id, status),
    INDEX idx_ir_task_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='投研任务';

-- ============================================================
-- 初始数据：左侧菜单 6 个产业
-- ============================================================
INSERT INTO industry_research_category (code, name, icon, sort_order, enabled, description) VALUES
('ai-compute',     'AI 算力产业链', '🧠', 10, 1, 'NVIDIA / 光模块 / PCB / HBM / 服务器全链条深度'),
('semiconductor',  '半导体设备',   '🔬', 20, 1, '光刻机 / 刻蚀 / 薄膜沉积 / 封测设备'),
('new-energy',     '新能源车',     '⚡', 30, 1, '电池 / 电机 / 电控 / 整车'),
('biotech',        '创新药',       '💊', 40, 1, 'ADC / GLP-1 / 双抗 / 出海'),
('consumer',       '新消费',       '🍵', 50, 1, '茶饮 / 咖啡 / 化妆品 / 零食'),
('robotics',       '人形机器人',   '🤖', 60, 1, '丝杠 / 谐波 / 传感器 / 整机')
ON DUPLICATE KEY UPDATE name = VALUES(name), icon = VALUES(icon), sort_order = VALUES(sort_order);