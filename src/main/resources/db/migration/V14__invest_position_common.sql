-- V14: invest_position_common (三池持仓/告警状态聚合表)
-- Source: SchemaInitializer.ensureInvestPositionCommon()
-- (data migration handled by Java post-Flyway: bootstrap migrator)

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
  COMMENT='三池持仓/告警状态聚合表（invest/tech_ai/potential 共用）';

-- (data migration handled by Java post-Flyway: bootstrap migrator)
-- Legacy columns DROP COLUMN on invest_stock_pool / tech_ai_pool / potential_pool
-- will be handled by Java code (SchemaInitializer.ensureInvestPositionCommon() data-migration path
-- still runs in legacy-fallback mode), since dropping columns is destructive and depends on
-- the legacy 3-pool data shape being present.
