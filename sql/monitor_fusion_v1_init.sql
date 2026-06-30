-- ============================================================================
-- Monitor Fusion v1 — 2026-06-30
-- 新增 9 列到 invest_position_common，支持固定价/ATR 振幅/%-止损/Server酱模板
-- SchemaInitializer.ensureMonitorFusionColumns() 已支持幂等升级，旧库无需手动跑。
-- 这个文件供新建库或历史脚本归一用。
-- ============================================================================

ALTER TABLE invest_position_common
    ADD COLUMN monitor_mode         VARCHAR(20)  NOT NULL DEFAULT 'standard' COMMENT '三态模式 standard|atr_strict|fixed_only',
    ADD COLUMN fixed_buy_price      DECIMAL(10,2) DEFAULT NULL                  COMMENT '固定买入价',
    ADD COLUMN fixed_sell_price     DECIMAL(10,2) DEFAULT NULL                  COMMENT '固定卖出价',
    ADD COLUMN fixed_buy_enabled    TINYINT(1)   NOT NULL DEFAULT 0            COMMENT '启用固定买入触发',
    ADD COLUMN fixed_sell_enabled   TINYINT(1)   NOT NULL DEFAULT 0            COMMENT '启用固定卖出触发',
    ADD COLUMN atr_alert_amplitude  DECIMAL(8,3) DEFAULT NULL                   COMMENT 'ATR 振幅倍数 (例如 1.500 = 1.5x ATR)',
    ADD COLUMN atr_alert_enabled    TINYINT(1)   NOT NULL DEFAULT 0            COMMENT '启用 ATR 振幅触发',
    ADD COLUMN stop_loss_pct        DECIMAL(8,2) DEFAULT NULL                  COMMENT '%-based 止损 (存负数, -8.00 表示 -8%)',
    ADD COLUMN serverchan_template  VARCHAR(50)  NOT NULL DEFAULT 'standard' COMMENT 'Server酱模板 standard|compact|verbose';
