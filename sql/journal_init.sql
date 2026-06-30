-- ============================================================
-- Trade Journal (journal_trade) — 2026-06-30
-- ============================================================

CREATE TABLE IF NOT EXISTS journal_trade (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  mode VARCHAR(10) NOT NULL,
  stock_code VARCHAR(20) NOT NULL,
  stock_name VARCHAR(50),

  entry_price DECIMAL(10,2) NOT NULL,
  entry_date DATETIME NOT NULL,
  entry_shares INT NOT NULL,
  account_at_entry DECIMAL(14,2),
  risk_percent DECIMAL(5,4),
  stop_price DECIMAL(10,2) NOT NULL,
  target_price DECIMAL(10,2),

  exit_price DECIMAL(10,2),
  exit_date DATETIME,
  exit_reason VARCHAR(30),
  initial_risk DECIMAL(10,2) NOT NULL,

  pnl_amount DECIMAL(14,2),
  r_multiple DECIMAL(8,4),
  is_open TINYINT DEFAULT 1,

  tags VARCHAR(200),
  setup_notes TEXT,
  review_notes TEXT,

  source VARCHAR(20),
  source_ref_id BIGINT,
  created_by VARCHAR(50),

  is_deleted TINYINT DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  INDEX idx_mode_open (mode, is_open),
  INDEX idx_stock (stock_code),
  INDEX idx_exit_date (exit_date),
  UNIQUE KEY uk_source_ref (source, source_ref_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
