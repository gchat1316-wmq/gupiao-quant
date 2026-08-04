-- V22: 省心 ETF 交易系统 (etf_* 前缀)
-- 模型规则（罗汉【省心】etf交易模型 2026-06-23 版）：
--   买入：只买 ETF，池子 ≤10 支；每支分 3 次进（1建仓+2加仓），中间可做T；
--        轻仓(趋势向下/涨幅大) ≤5000 元，中仓(横盘/趋势向上) 10000~20000 元
--   仓位：单支 ≤ 总资金 20%（≤2万）；组合持仓 ≤70%，永远留 30% 现金
--   止盈(摊薄成本)：+5% 减 1/3 → +10% 再减 1/3 → 剩 1/3 移动止盈(收盘跌破 20 日线才卖)
--   止损(摊薄成本)：宽基 -15% 减半 → -30% 再减半 → 留 1/4 长持，平稳后回补；
--                  行业/主题 -10% 减半 → -18% 无条件清仓
--   组合级保命：总资产从最高点回撤 20% → 整体降 1/4，冷静一周

CREATE TABLE IF NOT EXISTS etf_model_config (
    id                      BIGINT PRIMARY KEY,
    total_capital           DECIMAL(14,2) NOT NULL DEFAULT 100000.00,
    single_max_pct          DECIMAL(5,2)  NOT NULL DEFAULT 20.00,
    portfolio_max_pct       DECIMAL(5,2)  NOT NULL DEFAULT 70.00,
    light_batch_max_amount  DECIMAL(14,2) NOT NULL DEFAULT 5000.00,
    mid_batch_min_amount    DECIMAL(14,2) NOT NULL DEFAULT 10000.00,
    mid_batch_max_amount    DECIMAL(14,2) NOT NULL DEFAULT 20000.00,
    -- 近 20 日涨幅超过该值视为“涨幅大”→ 建议轻仓
    big_rise_threshold_pct  DECIMAL(5,2)  NOT NULL DEFAULT 15.00,
    portfolio_drawdown_pct  DECIMAL(5,2)  NOT NULL DEFAULT 20.00,
    calm_days               INT           NOT NULL DEFAULT 7,
    inception_date          DATE          NOT NULL DEFAULT '2026-06-23',
    nav_peak                DECIMAL(14,2),
    nav_peak_date           DATE,
    calm_until              DATE,
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO etf_model_config (id) VALUES (1);

CREATE TABLE IF NOT EXISTS etf_pool (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code      VARCHAR(20) NOT NULL,
    stock_name      VARCHAR(50),
    category        VARCHAR(10) NOT NULL DEFAULT 'SECTOR',
    -- BROAD(宽基: 标普/纳指/沪深300/中证500/A500) | SECTOR(行业/主题)
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE | REMOVED
    tp1_done        TINYINT(1) NOT NULL DEFAULT 0,
    -- +5% 已减 1/3
    tp2_done        TINYINT(1) NOT NULL DEFAULT 0,
    -- +10% 已再减 1/3
    sl1_done        TINYINT(1) NOT NULL DEFAULT 0,
    -- 宽基-15%/行业-10% 已减半
    sl2_done        TINYINT(1) NOT NULL DEFAULT 0,
    -- 宽基-30% 已再减半 / 行业-18% 已清仓
    recoup_status   VARCHAR(20) NOT NULL DEFAULT 'NONE',
    -- NONE | WAITING(止损减仓后待回补) | READY(周K连续2周收在5日线上方,可回补)
    recoup_weeks    INT NOT NULL DEFAULT 0,
    memo            VARCHAR(500),
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_etf_pool_code (stock_code),
    INDEX idx_etf_pool_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 预置当前 6 支持仓（代码可在页面修改；持仓从 0 开始由用户录单）
INSERT IGNORE INTO etf_pool (stock_code, stock_name, category) VALUES
    ('513100.SH', '纳指ETF',       'BROAD'),
    ('513500.SH', '标普500ETF',    'BROAD'),
    ('159516.SZ', '半导体设备ETF', 'SECTOR'),
    ('513120.SH', '港股创新药ETF', 'SECTOR'),
    ('513050.SH', '中概互联ETF',   'SECTOR'),
    ('159611.SZ', '电力ETF',       'SECTOR');

CREATE TABLE IF NOT EXISTS etf_trade (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    pool_id         BIGINT NOT NULL,
    stock_code      VARCHAR(20) NOT NULL,
    direction       VARCHAR(4) NOT NULL,
    -- BUY | SELL
    trade_type      VARCHAR(20) NOT NULL DEFAULT 'OTHER',
    -- OPEN(建仓) | ADD(加仓) | T_TRADE(做T) | RECOUP(回补)
    -- | TP1(+5%减1/3) | TP2(+10%再减1/3) | TRAIL_EXIT(移动止盈清仓)
    -- | SL1(止损减半) | SL2(止损再减半/清仓) | GUARD_CUT(保命降仓) | OTHER
    price           DECIMAL(10,3) NOT NULL,
    shares          INT NOT NULL,
    amount          DECIMAL(14,2) NOT NULL,
    trade_time      DATETIME NOT NULL,
    source          VARCHAR(10) NOT NULL DEFAULT 'MANUAL',
    -- MANUAL | QMT(光大证券QMT导入,预留)
    memo            VARCHAR(200),
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_etf_trade_pool (pool_id),
    INDEX idx_etf_trade_time (trade_time),
    CONSTRAINT fk_etf_trade_pool FOREIGN KEY (pool_id) REFERENCES etf_pool(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS etf_nav_snapshot (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    snap_date       DATE NOT NULL,
    market_value    DECIMAL(14,2) NOT NULL DEFAULT 0,
    cash            DECIMAL(14,2) NOT NULL DEFAULT 0,
    total_asset     DECIMAL(14,2) NOT NULL DEFAULT 0,
    peak_asset      DECIMAL(14,2),
    drawdown_pct    DECIMAL(8,2),
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_etf_nav_date (snap_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS etf_alert (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code      VARCHAR(20),
    -- NULL = 组合级信号(保命线等)
    signal_type     VARCHAR(40) NOT NULL,
    title           VARCHAR(200),
    content         TEXT,
    trigger_price   DECIMAL(10,3),
    trigger_at      DATETIME NOT NULL,
    pushed          TINYINT(1) NOT NULL DEFAULT 0,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_etf_alert_code_type_time (stock_code, signal_type, trigger_at),
    INDEX idx_etf_alert_time (trigger_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ETF 日 K（腾讯 fqkline 拉取；ETF 价格 3 位小数，precision 与 trade_stock_daily 不同故独立建表）
CREATE TABLE IF NOT EXISTS etf_daily_kline (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code      VARCHAR(20) NOT NULL,
    trade_date      DATE NOT NULL,
    open_price      DECIMAL(10,3),
    high_price      DECIMAL(10,3),
    low_price       DECIMAL(10,3),
    close_price     DECIMAL(10,3),
    volume          BIGINT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_etf_kline_code_date (stock_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
