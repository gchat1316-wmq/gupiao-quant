-- V12: invest_pool_meta (股票池元信息) + 3 seed rows
-- Source: SchemaInitializer.ensureInvestPoolMetaTable() + ensureInvestPoolMetaSeed()

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
  COMMENT='股票池类型元信息';

-- Seed: 3 pool metadata rows (tech_ai / innovative_drug / quality)
INSERT INTO invest_pool_meta
  (pool_type, display_name, cover_image_url, valuation_method_md, weekly_opportunity_md, display_order)
VALUES
  ('tech_ai', '科技AI', 'images/pool-covers/tech-ai.png', '### 10 倍 PS 市值法\n\n合理市值 = 预测营收 × 10\n\n- 当前市值 ≤ Y1 × 10：低估\n- 当前市值介于 Y1×10 ~ Y2×10：合理\n- 当前市值 ≥ Y2 × 10：泡沫\n\n适用于净利率接近 25% 的高科技成长股。', '本周暂无更新', 1),
  ('innovative_drug', '创新药', 'images/pool-covers/innovative_drug.svg', '### 创新药估值方法\n\n按 III 期管线 NPV 加总。\n\n待补充：\n- 风险调整成功率 (POS)\n- 上市峰值销售 (Peak Sales)\n- 净利率假设\n- 折现率与管线分摊', '本周暂无更新', 2),
  ('quality', '质量优选', 'images/pool-covers/quality.svg', '### 质量优选 · 巴菲特式估值\n\n**核心**：自由现金流优异、赚取真金白银、分红稳定。\n\n**简易模型**：现金流折现 + PE 匹配法\n\n合理 PE ≈ 预期未来 10 年净利润复合增长率 × 2\n\n**判断口诀**：若股票 PE 为 30 倍，需确认其未来十年能否实现 15% 复合增长。达标则考虑，不达标则放弃。\n\n代表企业：片仔癀、海天味业。', '本周暂无更新', 3)
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);
