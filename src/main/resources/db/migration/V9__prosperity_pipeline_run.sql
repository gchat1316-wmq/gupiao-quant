-- V9: prosperity_pipeline_run (热点选股 pipeline 运行记录)
-- Source: SchemaInitializer.ensurePipelineRunTable()

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
