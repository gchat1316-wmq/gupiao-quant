# Monitor Fusion — Design Spec

**Date:** 2026-06-30
**Status:** Approved
**Owner:** TechAi / Potential module owners

## 1. Problem

The codebase has three nearly-identical monitoring modules that duplicate state, scheduling, and notification paths:

| Existing module | File | Purpose |
|---|---|---|
| `TechAiService` | `service/TechAiService.java` | AI 监控 — minute-level pct + turnover alerts |
| `PotentialService` | `service/PotentialService.java` | 潜力监控 — pct alerts + ATR trailing stop + take-profit |
| `PriceMonitorService` | `service/PriceMonitorService.java` | Fixed-price monitor for `invest` pool |

Each has its own `@Scheduled` cron, its own per-stock pool table (`tech_ai_pool`, `potential_pool`), and its own set of `InvestPositionCommon` rows. They all funnel into the same `NotificationService.sendServerChan()` but track the same kind of alerts with slightly different rules. Code duplication is ~70%, and a user cannot say "monitor this one stock with a fixed price OR a 3% daily change OR an ATR-trailing stop" — they have to pick a pool first.

## 2. Goal

Unify all three monitors under a single **MonitorService** that evaluates **per-row any combination** of:

- **fixed-price trigger** — alert when price crosses a user-set buy/sell target
- **pct-change trigger** — alert when 1m / 5m / daily / 3-day / turnover change crosses user-set thresholds
- **ATR-amplitude trigger** — alert when intraday price range crosses `N × ATR(period)`
- **take-profit** — alert when price reaches `entry × (1 + take_profit_pct/100)`
- **stop-loss** — alert when price drops to `entry × (1 − stop_loss_pct/100)` (new) OR existing ATR-based trailing stop

Pool tables stay (`tech_ai_pool`, `potential_pool`). State table `invest_position_common` is extended with the new columns. Old URLs continue to work (thin aliases). A new `monitor.html` becomes the canonical UI; existing `tech-ai.html` and `potential.html` redirect.

## 3. User-facing requirements

| # | Requirement |
|---|---|
| U1 | On `/gp/monitor.html`, user can pick a pool (`stock` / `tech_ai` / `potential` / `all`) and see a single table. |
| U2 | When adding a stock to any pool, user can enable any combination of: fixed-buy, fixed-sell, ATR-amplitude, plus the existing 1m/5m/daily/3d/turnover thresholds. |
| U3 | When the latest quote breaches any enabled trigger on any watched stock, exactly one Server 酱 push fires per cooldown (cooldown per stock+trigger, default 10 min). |
| U4 | TP / SL trigger only sends an alert and marks the position as `tp_warned` / `stop_warned`. **No auto-fill** is written — user must press the 清仓 button to record the trade. |
| U5 | Old URLs (`/api/tech-ai/*`, `/api/potential/*`) still return data. Old pages (`tech-ai.html`, `potential.html`) show a "已迁移到 /monitor.html" banner. |
| U6 | Disabling a trigger flag (`*_enabled = 0`) suppresses that rule for that stock. Existing rows default all new flags to 0 so behavior doesn't change on rollout. |

## 4. Non-goals

- No cross-pool aggregation (e.g. "alert if tech_ai AND potential both fire").
- No holiday calendar (still weekday-only via `MON-FRI` cron).
- No multi-server / distributed lock — single Spring Boot process.
- No async notification — keep existing synchronous `NotificationService.sendServerChan()`.

## 5. Data model

### 5.1 Extend `invest_position_common`

```sql
ALTER TABLE invest_position_common
  ADD COLUMN monitor_mode           VARCHAR(20)  NOT NULL DEFAULT 'standard',  -- 'standard' | 'atr_strict' | 'fixed_only'
  ADD COLUMN fixed_buy_price        DECIMAL(10,2) NULL,
  ADD COLUMN fixed_sell_price       DECIMAL(10,2) NULL,
  ADD COLUMN fixed_buy_enabled      TINYINT(1)   NOT NULL DEFAULT 0,
  ADD COLUMN fixed_sell_enabled     TINYINT(1)   NOT NULL DEFAULT 0,
  ADD COLUMN atr_alert_amplitude    DECIMAL(8,3) NULL,                          -- e.g. 1.500 = 1.5 × ATR
  ADD COLUMN atr_alert_enabled      TINYINT(1)   NOT NULL DEFAULT 0,
  ADD COLUMN stop_loss_pct          DECIMAL(8,2) NULL,                          -- e.g. -8.00 means alert at entry × 0.92
  ADD COLUMN serverchan_template    VARCHAR(50)  NOT NULL DEFAULT 'standard';  -- 'standard' | 'compact' | 'verbose'
-- (Cooldown dedupe reuses InvestAlert.findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc; no separate last_eval_at column.)
```

Each new column is **nullable / default-off** so existing rows in `invest_position_common` (`pool_type` ∈ `{stock, tech_ai, potential}`) keep their old semantics after migration. New behavior is opt-in per row.

### 5.2 Pool tables (unchanged)

`tech_ai_pool`, `potential_pool` keep their identity-bearing schema (`stock_code`, `stock_name`, `status`, `memo`). No DDL. The MonitorService reads these for display + iterates `invest_position_common` for state.

### 5.3 Migration script

`sql/monitor_fusion_v1_init.sql`:

```sql
ALTER TABLE invest_position_common
  ADD COLUMN monitor_mode           VARCHAR(20)  NOT NULL DEFAULT 'standard',
  ADD COLUMN fixed_buy_price        DECIMAL(10,2) NULL,
  ADD COLUMN fixed_sell_price       DECIMAL(10,2) NULL,
  ADD COLUMN fixed_buy_enabled      TINYINT(1)   NOT NULL DEFAULT 0,
  ADD COLUMN fixed_sell_enabled     TINYINT(1)   NOT NULL DEFAULT 0,
  ADD COLUMN atr_alert_amplitude    DECIMAL(8,3) NULL,
  ADD COLUMN atr_alert_enabled      TINYINT(1)   NOT NULL DEFAULT 0,
  ADD COLUMN stop_loss_pct          DECIMAL(8,2) NULL,
  ADD COLUMN serverchan_template    VARCHAR(50)  NOT NULL DEFAULT 'standard';
```

For fresh DBs the same DDL goes into `SchemaInitializer.ensureMonitorColumns()` and runs every boot (idempotent via try/catch ALTER).

## 6. Service-layer changes

### 6.1 New `MonitorService`

**File:** `service/MonitorService.java` (new package `com.quant.service.monitor`)

**Public methods:**

```java
public List<MonitorPoolItemDTO> listPool(String poolType);       // poolType nullable → all
public MonitorPoolItemDTO addToPool(MonitorAddRequest req);
public void updateField(Long id, String field, Object value);    // patched via reflection on InvestPositionCommon
public void removeFromPool(Long id);                              // cascades
public int scan();                                                 // @Scheduled — every minute 9-15 weekdays
public int confirm();                                              // @Scheduled — 15:05 weekdays, close-of-day confirm
```

**Dependencies:**

- `InvestPositionCommonRepository`
- `TechAiPoolRepository`, `PotentialPoolRepository`, `InvestStockPoolRepository` (read identity + display)
- `TechAiAlertRuleEngine` (existing pct/turnover rules)
- `TechAiPositionEngine` (existing ATR trailing stop logic)
- `TechAiAtrCalculator`
- `AStockDataQuoteService` (primary quote) + `EastMoneyRealtimeQuoteService` (fallback)
- `NotificationService`
- `NotificationProperties`

**Internal helpers:**

- `evaluateFixedPrice(item, quote)` — returns signal list with `signalType ∈ {fixed_buy_hit, fixed_sell_hit}`; gated on `_enabled` flags
- `evaluatePctChange(item, quote, ctx)` — wraps existing `TechAiAlertRuleEngine`
- `evaluateAtrAmplitude(item, quote, atr)` — alert when `|latest − open_today| ≥ atr_alert_amplitude × ATR` and `atr_alert_enabled = 1`
- `evaluateTakeProfit(item, quote)` — alert when `latest ≥ entry × (1 + take_profit_pct / 100)`
- `evaluateStopLoss(item, quote, atr)` — alert when `latest ≤ entry × (1 − stop_loss_pct / 100)` (new % mode) OR `latest ≤ trailStop` (existing ATR mode)
- `pushAndRecord(signal)` — write `InvestAlert` row + `notificationService.sendServerChan(title, content)`, gated by cooldown via `InvestAlertRepository.findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc` (existing semantics: skip if last trigger was within `monitor.cooldown-minutes`)
- Cooldown defaults to `application.yml: monitor.cooldown-minutes = 10`

### 6.2 Modified services (delegate to MonitorService)

| Existing service | Change |
|---|---|
| `TechAiService.monitorQuotes()` | Body delegates to `MonitorService.scan(poolType="tech_ai")`; kept for backward compat on `@Scheduled` annotation |
| `PotentialService.monitorQuotes()` | Same — delegates to `MonitorService.scan(poolType="potential")` |
| `PriceMonitorService.check()` | Delegates to `MonitorService.scan(poolType="stock")` — fixed-price logic now lives in MonitorService |

Old services' `@Scheduled` annotations get removed or point to `@Scheduled(cron = "-")` (disabled). All real scanning runs in `MonitorService`.

### 6.3 New `MonitorRuleEngine`

**File:** `service/monitor/MonitorRuleEngine.java`

Wraps `TechAiAlertRuleEngine` + ATR helper logic. Pure-function class — `evaluate(MonitorItemContext) → List<MonitorSignal>`. Unit-testable in isolation.

### 6.4 New `MonitorAlertTemplate`

**File:** `service/monitor/MonitorAlertTemplate.java`

Renders Markdown for Server 酱 given `signalType` + `serverchan_template` setting. Supports three templates per spec.

## 7. Controller layer

### 7.1 New `MonitorController`

**File:** `controller/MonitorController.java`

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/monitor/pool` | `listPool(?poolType)` |
| POST | `/api/monitor/pool` | `addToPool(MonitorAddRequest)` |
| PATCH | `/api/monitor/pool/{id}/field` | `updateField(id, field, value)` |
| DELETE | `/api/monitor/pool/{id}` | `removeFromPool(id)` |
| GET | `/api/monitor/alerts` | Top-100 alerts (across all pool types) |
| POST | `/api/monitor/run` | Manual `scan()` trigger |
| GET | `/api/monitor/health` | `200 OK` if cron OK |

### 7.2 Backward-compat aliases

- `TechAiController` and `PotentialController` bodies call `monitorService.X(poolType="tech_ai"|"potential")`. Annotations stay. Keep `@RequestMapping("/api/tech-ai")` and `/api/potential` so existing frontend pages keep working until `monitor.html` ships.
- `PriceMonitorController` (if any) delegates similarly.

## 8. Notification flow

Single canonical path: `NotificationService.sendServerChan(title, content)` (existing, unchanged). All new signal types go through it. Markdown content picked from `serverchan_template` per row.

**New signal types** in `InvestAlert.signalType`:

| Signal type | Trigger |
|---|---|
| `fixed_buy_hit` | `latest ≤ fixed_buy_price` AND `fixed_buy_enabled = 1` |
| `fixed_sell_hit` | `latest ≥ fixed_sell_price` AND `fixed_sell_enabled = 1` |
| `atr_amplitude_alert` | `abs(latest − open_today) ≥ atr_alert_amplitude × ATR(atr_period)` AND `atr_alert_enabled = 1` |
| `take_profit_hit` | `latest ≥ entry × (1 + take_profit_pct/100)` |
| `stop_loss_hit` | `latest ≤ entry × (1 + stop_loss_pct/100)` (note: pct is stored negative) |
| `stop_loss_atr_hit` | `latest ≤ trail_stop_price` (existing ATR trailing-stop semantics) |

Cooldown per `(stock_code, signal_type)`: read latest `invest_alert` via `findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc`; if `latest.triggerAt + monitor.cooldown-minutes > now`, skip. Same-day dedupe retained for daily signals via `existsByStockCodeAndSignalTypeAndTriggerAtBetween`.

## 9. Configuration (`application.yml`)

```yaml
monitor:
  enabled: true
  cron: "0 */1 9-15 * * MON-FRI"          # intraday scan
  confirm-cron: "0 5 15 * * MON-FRI"      # close-of-day confirm
  cooldown-minutes: 10
  require-trading-time: true
  default-template: standard              # 'standard' | 'compact' | 'verbose'
  pool-types:
    - stock
    - tech_ai
    - potential
```

`NotificationProperties.Monitor` is added to the existing `@ConfigurationProperties` class. Old `notification.quote-monitor` and `notification.price-monitor` blocks become read-but-unused fallbacks for one release, then removed.

## 10. Frontend

### 10.1 New `/gp/monitor.html`

- Pool picker at top (4 buttons: `全部` / `投资池` / `AI监控` / `潜力监控`).
- Single pool table with columns: 股票 / 模式 / 现价 / 固定买入 / 固定卖出 / ATR振幅 / 止损% / 止盈% / 备注 / 操作.
- Expandable row: enable-toggle checkboxes for each trigger, threshold inputs, fill history, alert history.
- Sidebar (right): recent alerts.
- Loads `js/monitor.js` + `css/monitor.css` (new CSS file based on `tech-ai.css`).

### 10.2 Existing pages

- `tech-ai.html`: add top banner `📢 已迁移到 <a href="monitor.html">/monitor.html</a>`, redirect via JS on first interaction.
- `potential.html`: same banner.
- (No deletion in v1 — keep both pages for one release.)

## 11. Tests

| Test class | Coverage |
|---|---|
| `MonitorServiceTest` | fixed-price crossover (above/below), ATR amplitude trigger, cooldown dedupe, disabled flag suppresses firing, serverchan failure path returns gracefully |
| `MonitorRuleEngineTest` | pure-function evaluation: `evaluate(context) → signals[]` for each rule independently |
| `MonitorAlertTemplateTest` | three templates render expected Markdown |
| `MonitorControllerTest` (slice) | endpoint shapes, poolType filter, manual `/run` triggers scan |

Existing `TechAiServiceTest`, `PotentialServiceTest` continue passing (their bodies now delegate).

## 12. Rollout / migration

1. Land code branch with all new files + ALTER gated by `SchemaInitializer.ensureMonitorColumns()`.
2. New columns default off (`*_enabled = 0`) so current behavior is identical for all existing rows.
3. Deploy. Monitor Server 酱 output for 1 day — should be no new alerts.
4. User opt-in: from `/gp/monitor.html`, user enables new triggers per stock.
5. After at least 1 release with no incidents, optionally delete `tech-ai.html` + `potential.html` (deferred — keep for v1).

## 13. Out of scope / future

- ATR-amplitude *direction* filter (e.g. only alert on up-moves).
- Cross-stock correlation alerts.
- Feishu / 邮箱 channels via `NotificationProperties.WishPool` (already defined but unused).

## 14. Open questions

(none at spec time)
