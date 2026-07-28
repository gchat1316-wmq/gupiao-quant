-- V21: 科技趋势波段交易系统 (money_* 前缀)
-- 独立股票池 + 监控状态机 + 持仓/事件/成交/日指标缓存

CREATE TABLE IF NOT EXISTS money_stock_pool (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL DEFAULT 0,
    stock_code      VARCHAR(20) NOT NULL,
    stock_name      VARCHAR(50),
    sector_tag      VARCHAR(50),
    source          VARCHAR(30) NOT NULL DEFAULT 'MANUAL',
    -- MANUAL | API | PROSPERITY | TECH_AI | POTENTIAL | INVEST | OTHER
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE | PAUSED | REMOVED
    paper_mode      TINYINT(1) NOT NULL DEFAULT 0,
    memo            VARCHAR(500),
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_money_pool_user_code (user_id, stock_code),
    INDEX idx_money_pool_status (status),
    INDEX idx_money_pool_source (source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS money_watch (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    pool_id         BIGINT NOT NULL,
    user_id         BIGINT NOT NULL DEFAULT 0,
    stock_code      VARCHAR(20) NOT NULL,
    stock_name      VARCHAR(50),
    status          VARCHAR(30) NOT NULL DEFAULT 'SCREENING',
    -- SCREENING | WATCH_PULLBACK | WATCH_BREAKOUT | BUY_SIGNAL
    -- | HOLDING | PARTIAL_EXIT | CLOSED | INVALID
    active_flag     TINYINT(1) NOT NULL DEFAULT 1,
    invalid_reason  VARCHAR(200),
    sector_tag      VARCHAR(50),
    screen_passed   TINYINT(1) NOT NULL DEFAULT 0,
    screen_detail   JSON,
    market_regime   VARCHAR(20),
    index_above_ma20 TINYINT(1),
    buy_signal_type VARCHAR(20),
    buy_signal_at   DATETIME,
    buy_signal_price DECIMAL(10,2),
    signal_expire_at DATETIME,
    consecutive_stops INT NOT NULL DEFAULT 0,
    paused_until    DATE,
    memo            VARCHAR(500),
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_money_watch_status (status),
    INDEX idx_money_watch_pool (pool_id),
    INDEX idx_money_watch_active (user_id, stock_code, active_flag),
    -- 注意：不把 active_flag 放进唯一键（MySQL 多个 0 会冲突）；活跃唯一由 pool_id + 应用层保证
    CONSTRAINT fk_money_watch_pool FOREIGN KEY (pool_id) REFERENCES money_stock_pool(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS money_setup (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    watch_id        BIGINT NOT NULL,
    setup_type      VARCHAR(20) NOT NULL,
    -- PULLBACK | BREAKOUT
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE | TRIGGERED | EXPIRED
    limit_up_dates  JSON,
    limit_up_count  INT,
    platform_low    DECIMAL(10,2),
    platform_open   DECIMAL(10,2),
    limit_up_volume BIGINT,
    pullback_low    DECIMAL(10,2),
    platform_high   DECIMAL(10,2),
    platform_days   INT,
    breakout_volume_ratio DECIMAL(6,2),
    trigger_price   DECIMAL(10,2),
    trigger_at      DATETIME,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_money_setup_watch (watch_id),
    INDEX idx_money_setup_status (status),
    CONSTRAINT fk_money_setup_watch FOREIGN KEY (watch_id) REFERENCES money_watch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS money_position (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    watch_id        BIGINT NOT NULL,
    pool_id         BIGINT NOT NULL,
    user_id         BIGINT NOT NULL DEFAULT 0,
    stock_code      VARCHAR(20) NOT NULL,
    stock_name      VARCHAR(50),
    buy_type        VARCHAR(20) NOT NULL,
    -- PULLBACK | BREAKOUT
    entry_price     DECIMAL(10,2) NOT NULL,
    entry_date      DATETIME NOT NULL,
    entry_shares    INT,
    position_pct    DECIMAL(5,2) NOT NULL DEFAULT 100.00,
    peak_price      DECIMAL(10,2),
    profit_tier     VARCHAR(10) NOT NULL DEFAULT 'T0',
    -- T0(<15%) | T1(15-30%) | T2(30-50%) | T3(>50%)
    stop_primary    DECIMAL(10,2),
    stop_secondary  DECIMAL(10,2),
    trailing_stop   DECIMAL(10,2),
    cost_stop       DECIMAL(10,2),
    add_position_done TINYINT(1) NOT NULL DEFAULT 0,
    add_entry_price DECIMAL(10,2),
    add_shares      INT,
    ma_snapshot     JSON,
    below_ma20_days INT NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'HOLDING',
    -- HOLDING | PARTIAL_EXIT | CLOSED
    closed_at       DATETIME,
    close_reason    VARCHAR(50),
    realized_pnl    DECIMAL(14,2),
    realized_pnl_pct DECIMAL(8,2),
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_money_pos_watch (watch_id),
    INDEX idx_money_pos_status (status),
    INDEX idx_money_pos_code (stock_code),
    CONSTRAINT fk_money_pos_watch FOREIGN KEY (watch_id) REFERENCES money_watch(id),
    CONSTRAINT fk_money_pos_pool FOREIGN KEY (pool_id) REFERENCES money_stock_pool(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS money_event (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    watch_id        BIGINT,
    position_id     BIGINT,
    pool_id         BIGINT,
    stock_code      VARCHAR(20) NOT NULL,
    stock_name      VARCHAR(50),
    event_type      VARCHAR(40) NOT NULL,
    severity        VARCHAR(10) NOT NULL DEFAULT 'INFO',
    -- INFO | WARN | ACTION
    title           VARCHAR(120),
    content         TEXT,
    trigger_price   DECIMAL(10,2),
    trigger_data    JSON,
    pushed          TINYINT(1) NOT NULL DEFAULT 0,
    acknowledged    TINYINT(1) NOT NULL DEFAULT 0,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_money_event_code_type_time (stock_code, event_type, created_at),
    INDEX idx_money_event_watch (watch_id),
    INDEX idx_money_event_ack (acknowledged, severity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS money_trade_leg (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    position_id     BIGINT NOT NULL,
    watch_id        BIGINT,
    stock_code      VARCHAR(20) NOT NULL,
    leg_type        VARCHAR(10) NOT NULL,
    -- BUY | SELL | ADD
    price           DECIMAL(10,2) NOT NULL,
    shares          INT,
    amount          DECIMAL(14,2),
    trade_date      DATETIME NOT NULL,
    source          VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    -- MANUAL | SYSTEM_PAPER
    linked_event_id BIGINT,
    memo            VARCHAR(200),
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_money_leg_position (position_id),
    INDEX idx_money_leg_code (stock_code),
    CONSTRAINT fk_money_leg_position FOREIGN KEY (position_id) REFERENCES money_position(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS money_daily_metrics (
    stock_code      VARCHAR(20) NOT NULL,
    trade_date      DATE NOT NULL,
    ma5             DECIMAL(10,2),
    ma10            DECIMAL(10,2),
    ma20            DECIMAL(10,2),
    ma60            DECIMAL(10,2),
    ma20_slope      DECIMAL(10,6),
    vol_ma5         BIGINT,
    vol_ma20        BIGINT,
    vol_ratio       DECIMAL(8,4),
    is_limit_up     TINYINT(1) NOT NULL DEFAULT 0,
    close_price     DECIMAL(10,2),
    volume          BIGINT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (stock_code, trade_date),
    INDEX idx_money_metrics_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
