-- 学习搭子 模块表
USE `gupiao_quant`;

CREATE TABLE IF NOT EXISTS `study_category` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(32) NOT NULL,
  `sort` int(11) DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_study_cat_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公开项目分类';

CREATE TABLE IF NOT EXISTS `study_course` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `title` varchar(200) NOT NULL COMMENT '课程/项目标题',
  `summary` text COMMENT '课程简介',
  `cover_text` varchar(200) DEFAULT NULL COMMENT '封面文字 / emoji',
  `cover_color` varchar(20) DEFAULT '#e8f5e9',
  `owner` varchar(64) DEFAULT '智谱清言创建',
  `source_type` varchar(20) DEFAULT 'upload' COMMENT 'upload/cloud/url',
  `visibility` varchar(10) DEFAULT 'private' COMMENT 'private/public',
  `status` varchar(20) DEFAULT 'ready' COMMENT 'processing/ready',
  `progress` int(11) DEFAULT 100 COMMENT '解析进度 0-100',
  `category_id` int(11) DEFAULT NULL,
  `learn_status` varchar(20) DEFAULT 'pending' COMMENT 'pending/learning/done',
  `mastered_cnt` int(11) DEFAULT 0,
  `total_cnt` int(11) DEFAULT 0,
  `learner_cnt` int(11) DEFAULT 0 COMMENT '已学习人数',
  `book_cover_url` varchar(500) DEFAULT NULL,
  `recommend_images` text COMMENT '推荐学习配图 JSON 数组',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_course_visibility` (`visibility`),
  KEY `idx_course_category` (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='学习项目/课程';

CREATE TABLE IF NOT EXISTS `study_material` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `course_id` bigint(20) NOT NULL,
  `file_name` varchar(255) NOT NULL,
  `file_type` varchar(20) DEFAULT NULL,
  `file_path` varchar(500) DEFAULT NULL,
  `size` bigint(20) DEFAULT 0,
  `parse_status` varchar(20) DEFAULT 'pending' COMMENT 'pending/parsing/done/failed',
  `progress` int(11) DEFAULT 0,
  `extracted_text` mediumtext COMMENT '抽取出的文本(摘要)',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mat_course` (`course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='上传学习资料';

CREATE TABLE IF NOT EXISTS `study_knowledge_node` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `course_id` bigint(20) NOT NULL,
  `parent_id` bigint(20) DEFAULT NULL,
  `title` varchar(200) NOT NULL,
  `summary` text,
  `definition` text COMMENT '定义/核心思想',
  `sort` int(11) DEFAULT 0,
  `level` int(11) DEFAULT 1,
  `mastered` tinyint(1) DEFAULT 0,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_node_course` (`course_id`),
  KEY `idx_node_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识点树';

CREATE TABLE IF NOT EXISTS `study_card` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `node_id` bigint(20) NOT NULL,
  `card_type` varchar(20) NOT NULL COMMENT 'ai_detail / flash',
  `stage` varchar(50) DEFAULT NULL COMMENT '阶段一/阶段二 等',
  `title` varchar(200) DEFAULT NULL,
  `body` text,
  `image_url` varchar(500) DEFAULT NULL,
  `sort` int(11) DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_card_node` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识卡片';

CREATE TABLE IF NOT EXISTS `study_quiz` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `node_id` bigint(20) NOT NULL,
  `stem` text NOT NULL COMMENT '题干',
  `options_json` text NOT NULL COMMENT '选项 JSON [{"key":"A","text":"..."}]',
  `answer` varchar(8) NOT NULL COMMENT '正确选项 key',
  `analysis` text COMMENT '解析',
  `sort` int(11) DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_quiz_node` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='测验题目';

CREATE TABLE IF NOT EXISTS `study_quiz_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `quiz_id` bigint(20) NOT NULL,
  `picked` varchar(8) DEFAULT NULL,
  `correct` tinyint(1) DEFAULT 0,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_qr_quiz` (`quiz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='答题记录';
