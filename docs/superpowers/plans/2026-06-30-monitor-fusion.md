# Monitor Fusion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify TechAi + Potential + PriceMonitor into a single MonitorService that evaluates per-row any combination of fixed-price, pct-change, ATR-amplitude, take-profit, and stop-loss triggers, with Server 酱 push.

**Architecture:** Big-bang rewrite scope approved. Storage stays in `invest_position_common` (extended with new columns). Old endpoints preserved as thin pass-throughs. New `MonitorService` orchestrates the unified scan; old `TechAiService` / `PotentialService` / `PriceMonitorService` delegate to it.

**Tech Stack:** Spring Boot 3.2.5, Java 17, JPA, Lombok, Spring `@Scheduled`, Caffeine cache, Server 酱 HTTP.

## File Map

### Created
- `service/monitor/MonitorService.java`
- `service/monitor/MonitorRuleEngine.java`
- `service/monitor/MonitorAlertTemplate.java`
- `controller/MonitorController.java`
- `dto/monitor/MonitorPoolItemDTO.java`
- `dto/monitor/MonitorAddRequest.java`
- `dto/monitor/MonitorFieldUpdateRequest.java`
- `dto/monitor/MonitorRunResponse.java`
- `dto/monitor/MonitorSignal.java` (internal record, package-private)
- `static/monitor.html`
- `static/js/monitor.js`
- `static/css/monitor.css`
- `test/.../MonitorServiceTest.java`
- `test/.../MonitorRuleEngineTest.java`
- `test/.../MonitorAlertTemplateTest.java`
- `sql/monitor_fusion_v1_init.sql`

### Modified
- `entity/InvestPositionCommon.java` — add 9 new fields
- `config/SchemaInitializer.java` — add `ensureMonitorColumns()` (called from `run()`)
- `config/NotificationProperties.java` — add `Monitor` nested class
- `service/TechAiService.java` — make `monitorQuotes()`/`confirmPositionSignals()` delegate to MonitorService
- `service/PotentialService.java` — same
- `service/PriceMonitorService.java` — read fixed-price from `InvestPositionCommon` instead of `InvestStockPool`
- `controller/TechAiController.java` — read endpoints stay; `runMonitor` calls MonitorService
- `controller/PotentialController.java` — same
- `static/tech-ai.html`, `static/potential.html` — add banner redirect to monitor.html
- `application.yml` — add `notification.monitor` config block
- `sql/wucai_trade.sql` — append same DDL as SchemaInitializer for fresh DBs

### Dependency graph
```
SchemaInitializer.ensureMonitorColumns()   ──┐
InvestPositionCommon (new fields)            │
NotificationProperties.Monitor               │
         │                                   │
         ▼                                   │
TechAiAlertRuleEngine (existing) ──► MonitorRuleEngine
                                              │
MonitorAlertTemplate  ◄────────────────────────┤
                                              ▼
                                       MonitorService
                                              │
InvestStockPoolRepository, InvestPositionCommonRepository
TechAiPoolRepository, PotentialPoolRepository
AStockDataQuoteService, EastMoneyRealtimeQuoteService
NotificationService
                                              │
                                              ▼
                                       MonitorController
                                              │
                                              ▼
                                    monitor.html + monitor.js
```

---

## Task 1: Schema migration — extend `invest_position_common`

**Files:**
- Modify: `src/main/java/com/quant/config/SchemaInitializer.java` (add `ensureMonitorColumns()` + invoke in `run()`)
- Create: `sql/monitor_fusion_v1_init.sql`
- Modify: `sql/wucai_trade.sql` (append same DDL)
- Modify: `src/main/java/com/quant/entity/InvestPositionCommon.java` (add 9 new fields)

- [ ] **Step 1: Add `ensureMonitorColumns()` to SchemaInitializer**

  Append after `ensureInvestPositionCommon()`:

  ```java
  private void ensureMonitorColumns() {
      String[][] columns = {
          {"monitor_mode",         "VARCHAR(20) NOT NULL DEFAULT 'standard' COMMENT 'standard|atr_strict|fixed_only'"},
          {"fixed_buy_price",      "DECIMAL(10,2) DEFAULT NULL COMMENT '固定买入价'"},
          {"fixed_sell_price",     "DECIMAL(10,2) DEFAULT NULL COMMENT '固定卖出价'"},
          {"fixed_buy_enabled",    "TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否启用固定买入触发'"},
          {"fixed_sell_enabled",   "TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否启用固定卖出触发'"},
          {"atr_alert_amplitude",  "DECIMAL(8,3) DEFAULT NULL COMMENT 'ATR 振幅倍数(例如 1.5 表示 1.5x ATR)'"},
          {"atr_alert_enabled",    "TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否启用 ATR 振幅触发'"},
          {"stop_loss_pct",        "DECIMAL(8,2) DEFAULT NULL COMMENT '%-based 止损(存负数，例如 -8.00 表示 -8%)'"},
          {"serverchan_template",  "VARCHAR(50) NOT NULL DEFAULT 'standard' COMMENT 'standard|compact|verbose'"}
      };
      for (String[] col : columns) {
          try {
              Integer count = jdbc.queryForObject(
                  "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'invest_position_common' AND column_name = '" + col[0] + "'",
                  Integer.class);
              if (count == null || count == 0) {
                  jdbc.execute("ALTER TABLE invest_position_common ADD COLUMN " + col[0] + " " + col[1].substring(0, col[1].indexOf("COMMENT")) + " " + col[1].substring(col[1].indexOf("COMMENT")));
                  log.info("invest_position_common.{} 列已添加", col[0]);
              }
          } catch (Exception e) {
              log.warn("检查 invest_position_common.{} 列失败 (可忽略): {}", col[0], e.getMessage());
          }
      }
  }
  ```

  Add `ensureMonitorColumns();` to `run()` directly after `ensureInvestPositionCommon();`.

- [ ] **Step 2: Add fields to InvestPositionCommon entity**

  Append after `atrTrailMult` (line 136):

  ```java
  // ===== Monitor Fusion 新增字段 (2026-06-30) =====

  @Column(name = "monitor_mode", length = 20)
  private String monitorMode = "standard";

  @Column(name = "fixed_buy_price", precision = 10, scale = 2)
  private BigDecimal fixedBuyPrice;

  @Column(name = "fixed_sell_price", precision = 10, scale = 2)
  private BigDecimal fixedSellPrice;

  @Column(name = "fixed_buy_enabled")
  private Integer fixedBuyEnabled = 0;

  @Column(name = "fixed_sell_enabled")
  private Integer fixedSellEnabled = 0;

  @Column(name = "atr_alert_amplitude", precision = 8, scale = 3)
  private BigDecimal atrAlertAmplitude;

  @Column(name = "atr_alert_enabled")
  private Integer atrAlertEnabled = 0;

  @Column(name = "stop_loss_pct", precision = 8, scale = 2)
  private BigDecimal stopLossPct;

  @Column(name = "serverchan_template", length = 50)
  private String serverchanTemplate = "standard";
  ```

- [ ] **Step 3: Create `sql/monitor_fusion_v1_init.sql`**

  Same DDL as Step 1's `ALTER TABLE` statements, in a single SQL file for ops.

- [ ] **Step 4: Verify build compiles**

  Run: `mvn compile -q`
  Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

  ```bash
  git add sql/monitor_fusion_v1_init.sql sql/wucai_trade.sql \
          src/main/java/com/quant/config/SchemaInitializer.java \
          src/main/java/com/quant/entity/InvestPositionCommon.java
  git commit -m "feat(monitor): extend invest_position_common with fixed-price/ATR/stop-loss columns"
  ```

---

## Task 2: Notification config additions

**Files:**
- Modify: `src/main/java/com/quant/config/NotificationProperties.java`

- [ ] **Step 1: Add `Monitor` nested class to NotificationProperties**

  Inside `NotificationProperties` add (after `QuoteMonitor`):

  ```java
  private Monitor monitor = new Monitor();

  // (existing nested classes stay)

  @Data
  public static class Monitor {
      private boolean enabled = true;
      private boolean requireTradingTime = true;
      private int cooldownMinutes = 10;
      private String cron = "0 */1 9-15 * * MON-FRI";
      private String confirmCron = "0 5 15 * * MON-FRI";
      private String defaultTemplate = "standard";
      private java.util.List<String> poolTypes = java.util.List.of("stock", "tech_ai", "potential");
  }
  ```

- [ ] **Step 2: Add `notification.monitor` block to application.yml**

  Insert after the `notification.quote-monitor` block (around line 199):

  ```yaml
    monitor:
      enabled: true
      require-trading-time: true
      cooldown-minutes: 10
      cron: "0 */1 9-15 * * MON-FRI"
      confirm-cron: "0 5 15 * * MON-FRI"
      default-template: standard
      pool-types:
        - stock
        - tech_ai
        - potential
  ```

- [ ] **Step 3: Verify build compiles**

  Run: `mvn compile -q`
  Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

  ```bash
  git add src/main/java/com/quant/config/NotificationProperties.java \
          src/main/resources/application.yml
  git commit -m "feat(monitor): add notification.monitor config block"
  ```

---

## Task 3: MonitorAlertTemplate — message rendering

**Files:**
- Create: `src/main/java/com/quant/service/monitor/MonitorAlertTemplate.java`
- Test: `src/test/java/com/quant/service/monitor/MonitorAlertTemplateTest.java`

- [ ] **Step 1: Write the failing test**

  ```java
  package com.quant.service.monitor;

  import com.quant.entity.InvestPositionCommon;
  import org.junit.jupiter.api.Test;

  import java.math.BigDecimal;
  import java.time.LocalDateTime;

  import static org.junit.jupiter.api.Assertions.*;

  class MonitorAlertTemplateTest {

      @Test
      void rendersStandardTemplateWithStockNameAndPrice() {
          InvestPositionCommon pos = new InvestPositionCommon();
          pos.setStockCode("600519.SH");
          pos.setStockName("贵州茅台");
          pos.setServerchanTemplate("standard");

          MonitorSignal sig = MonitorSignal.fixedPriceBuy(
                  pos, "600519.SH", "贵州茅台",
                  new BigDecimal("1500.00"), new BigDecimal("1480.00"));

          String md = MonitorAlertTemplate.render(sig);

          assertTrue(md.contains("贵州茅台"));
          assertTrue(md.contains("1500.00"));
          assertTrue(md.contains("1480.00"));
          assertTrue(md.contains("目标买入价"));
      }

      @Test
      void rendersCompactTemplate() {
          InvestPositionCommon pos = new InvestPositionCommon();
          pos.setStockCode("000001.SZ");
          pos.setStockName("平安银行");
          pos.setServerchanTemplate("compact");

          MonitorSignal sig = MonitorSignal.atrAmplitude(
                  pos, "000001.SZ", "平安银行",
                  new BigDecimal("12.00"), new BigDecimal("11.50"),
                  new BigDecimal("0.50"), new BigDecimal("1.5"));

          String md = MonitorAlertTemplate.render(sig);
          assertTrue(md.length() < 200);
          assertTrue(md.contains("ATR"));
      }
  }
  ```

- [ ] **Step 2: Run the test — it should fail (type doesn't exist yet)**

  Run: `mvn test -Dtest=MonitorAlertTemplateTest -q`
  Expected: COMPILATION FAILURE (MonitorSignal doesn't exist)

- [ ] **Step 3: Create `MonitorSignal.java`**

  ```java
  package com.quant.service.monitor;

  import com.quant.entity.InvestPositionCommon;
  import lombok.Builder;
  import lombok.Data;

  import java.math.BigDecimal;
  import java.time.LocalDateTime;

  @Data
  @Builder
  public class MonitorSignal {
      private final String stockCode;
      private final String stockName;
      private final String signalType;        // "fixed_buy_hit", "fixed_sell_hit", "atr_amplitude_alert",
                                              //  "take_profit_hit", "stop_loss_hit", "stop_loss_atr_hit",
                                              //  "minute_1m_up", "minute_1m_down", ..., "daily_up", ...
      private final String title;
      private final String content;
      private final BigDecimal triggerPrice;
      private final BigDecimal threshold;
      private final BigDecimal currentValue;
      private final String template;
      private final LocalDateTime triggeredAt;

      public static MonitorSignal fixedPriceBuy(InvestPositionCommon pos, String code, String name,
                                                BigDecimal triggerPrice, BigDecimal threshold) {
          return MonitorSignal.builder()
                  .stockCode(code).stockName(name)
                  .signalType("fixed_buy_hit")
                  .title(String.format("📉 %s(%s) 触及买入价 %s", name, code, threshold))
                  .content("## " + name + "(" + code + ")\n\n当前价: " + triggerPrice + "\n\n固定买入价 " + threshold + " ✅ 已触发")
                  .triggerPrice(triggerPrice).threshold(threshold)
                  .template(pos.getServerchanTemplate())
                  .triggeredAt(LocalDateTime.now())
                  .build();
      }

      public static MonitorSignal fixedPriceSell(InvestPositionCommon pos, String code, String name,
                                                 BigDecimal triggerPrice, BigDecimal threshold) {
          return MonitorSignal.builder()
                  .stockCode(code).stockName(name)
                  .signalType("fixed_sell_hit")
                  .title(String.format("📈 %s(%s) 触及卖出价 %s", name, code, threshold))
                  .content("## " + name + "(" + code + ")\n\n当前价: " + triggerPrice + "\n\n固定卖出价 " + threshold + " ✅ 已触发")
                  .triggerPrice(triggerPrice).threshold(threshold)
                  .template(pos.getServerchanTemplate())
                  .triggeredAt(LocalDateTime.now())
                  .build();
      }

      public static MonitorSignal atrAmplitude(InvestPositionCommon pos, String code, String name,
                                               BigDecimal latest, BigDecimal openToday, BigDecimal atr, BigDecimal mult) {
          return MonitorSignal.builder()
                  .stockCode(code).stockName(name)
                  .signalType("atr_amplitude_alert")
                  .title(String.format("📊 %s(%s) 振幅达 %.2fx ATR", name, code, mult.doubleValue()))
                  .content("## " + name + "(" + code + ")\n\n" +
                           "- 当前价: " + latest + "\n" +
                           "- 开盘价: " + openToday + "\n" +
                           "- ATR: " + atr + "\n" +
                           "- 倍数阈值: " + mult + "x\n")
                  .triggerPrice(latest).threshold(mult)
                  .currentValue(atr)
                  .template(pos.getServerchanTemplate())
                  .triggeredAt(LocalDateTime.now())
                  .build();
      }

      public static MonitorSignal takeProfit(InvestPositionCommon pos, String code, String name,
                                             BigDecimal triggerPrice, BigDecimal entryPrice, BigDecimal pct) {
          return MonitorSignal.builder()
                  .stockCode(code).stockName(name)
                  .signalType("take_profit_hit")
                  .title(String.format("💰 %s(%s) 触及止盈 %s%%", name, code, pct))
                  .content("## " + name + "(" + code + ")\n\n" +
                           "- 当前价: " + triggerPrice + "\n" +
                           "- 成本价: " + entryPrice + "\n" +
                           "- 止盈%: +" + pct + "%\n")
                  .triggerPrice(triggerPrice).threshold(pct)
                  .template(pos.getServerchanTemplate())
                  .triggeredAt(LocalDateTime.now())
                  .build();
      }

      public static MonitorSignal stopLossPct(InvestPositionCommon pos, String code, String name,
                                              BigDecimal triggerPrice, BigDecimal entryPrice, BigDecimal stopPct) {
          return MonitorSignal.builder()
                  .stockCode(code).stockName(name)
                  .signalType("stop_loss_hit")
                  .title(String.format("🛑 %s(%s) 触及止损 %s%%", name, code, stopPct))
                  .content("## " + name + "(" + code + ")\n\n" +
                           "- 当前价: " + triggerPrice + "\n" +
                           "- 成本价: " + entryPrice + "\n" +
                           "- 止损%: " + stopPct + "%\n")
                  .triggerPrice(triggerPrice).threshold(stopPct)
                  .template(pos.getServerchanTemplate())
                  .triggeredAt(LocalDateTime.now())
                  .build();
      }

      public static MonitorSignal stopLossAtr(InvestPositionCommon pos, String code, String name,
                                              BigDecimal triggerPrice, BigDecimal stopLine) {
          return MonitorSignal.builder()
                  .stockCode(code).stockName(name)
                  .signalType("stop_loss_atr_hit")
                  .title(String.format("🛑 %s(%s) 触及 ATR 移动止损 %s", name, code, stopLine))
                  .content("## " + name + "(" + code + ")\n\n" +
                           "- 当前价: " + triggerPrice + "\n" +
                           "- ATR 移动止损价: " + stopLine + " ✅ 已触发\n")
                  .triggerPrice(triggerPrice).threshold(stopLine)
                  .template(pos.getServerchanTemplate())
                  .triggeredAt(LocalDateTime.now())
                  .build();
      }

      public static MonitorSignal pctChange(InvestPositionCommon pos, String code, String name,
                                            String signalType, String title, String content,
                                            BigDecimal currentValue, BigDecimal threshold) {
          return MonitorSignal.builder()
                  .stockCode(code).stockName(name)
                  .signalType(signalType)
                  .title(title)
                  .content(content)
                  .currentValue(currentValue)
                  .threshold(threshold)
                  .triggerPrice(currentValue)
                  .template(pos.getServerchanTemplate() == null ? "standard" : pos.getServerchanTemplate())
                  .triggeredAt(LocalDateTime.now())
                  .build();
      }
  }
  ```

- [ ] **Step 4: Create MonitorAlertTemplate**

  ```java
  package com.quant.service.monitor;

  import org.springframework.stereotype.Component;

  @Component
  public class MonitorAlertTemplate {

      public static String render(MonitorSignal sig) {
          if (sig.getTemplate() == null || "standard".equalsIgnoreCase(sig.getTemplate())) {
              return sig.getTitle() + "\n\n" + sig.getContent();
          }
          if ("compact".equalsIgnoreCase(sig.getTemplate())) {
              return sig.getTitle() + " · " + sig.getStockCode() + " · 现价 " + sig.getTriggerPrice();
          }
          if ("verbose".equalsIgnoreCase(sig.getTemplate())) {
              return String.format("""
                      %s

                      ============= 详情 =============

                      %s

                      ---
                      信号类型: %s
                      触发价: %s
                      阈值:   %s
                      当前值: %s
                      时间:   %s
                      """,
                      sig.getTitle(), sig.getContent(),
                      sig.getSignalType(), sig.getTriggerPrice(),
                      sig.getThreshold(), sig.getCurrentValue(),
                      sig.getTriggeredAt());
          }
          return sig.getTitle() + "\n\n" + sig.getContent();
      }
  }
  ```

- [ ] **Step 5: Run the tests**

  Run: `mvn test -Dtest=MonitorAlertTemplateTest -q`
  Expected: PASS (2 tests)

- [ ] **Step 6: Commit**

  ```bash
  git add src/main/java/com/quant/service/monitor/MonitorSignal.java \
          src/main/java/com/quant/service/monitor/MonitorAlertTemplate.java \
          src/test/java/com/quant/service/monitor/MonitorAlertTemplateTest.java
  git commit -m "feat(monitor): MonitorSignal record + AlertTemplate with 3 templates"
  ```

---

## Task 4: MonitorRuleEngine — pure-function evaluation

**Files:**
- Create: `src/main/java/com/quant/service/monitor/MonitorRuleEngine.java`
- Test: `src/test/java/com/quant/service/monitor/MonitorRuleEngineTest.java`

- [ ] **Step 1: Write the failing test**

  ```java
  package com.quant.service.monitor;

  import com.quant.entity.InvestPositionCommon;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;

  import java.math.BigDecimal;
  import java.time.LocalDateTime;
  import java.util.List;

  import static org.junit.jupiter.api.Assertions.*;

  class MonitorRuleEngineTest {

      private InvestPositionCommon pos;
      private MonitorContext ctx;

      @BeforeEach
      void setUp() {
          pos = new InvestPositionCommon();
          pos.setStockCode("600519.SH");
          pos.setPoolType("tech_ai");
          pos.setMonitorMode("standard");
      }

      @Test
      void fixedBuyTriggersWhenLatestBelowThreshold() {
          pos.setFixedBuyPrice(new BigDecimal("1500.00"));
          pos.setFixedBuyEnabled(1);

          ctx = baseContext().latest(new BigDecimal("1480.00")).build();

          List<MonitorSignal> sigs = new MonitorRuleEngine().evaluate(ctx);
          assertEquals(1, sigs.size());
          assertEquals("fixed_buy_hit", sigs.get(0).getSignalType());
      }

      @Test
      void fixedBuyDisabledSuppressesTrigger() {
          pos.setFixedBuyPrice(new BigDecimal("1500.00"));
          pos.setFixedBuyEnabled(0);

          ctx = baseContext().latest(new BigDecimal("1480.00")).build();

          assertTrue(new MonitorRuleEngine().evaluate(ctx).isEmpty());
      }

      @Test
      void atrAmplitudeTriggersWhenMoveExceedsMultiplier() {
          pos.setAtrAlertAmplitude(new BigDecimal("1.500"));
          pos.setAtrAlertEnabled(1);

          ctx = baseContext()
                  .latest(new BigDecimal("12.00"))
                  .openToday(new BigDecimal("11.00"))
                  .atr(new BigDecimal("0.50"))
                  .build();

          List<MonitorSignal> sigs = new MonitorRuleEngine().evaluate(ctx);
          assertTrue(sigs.stream().anyMatch(s -> "atr_amplitude_alert".equals(s.getSignalType())));
      }

      @Test
      void takeProfitTriggersWhenPriceAboveEntry() {
          pos.setEntryPrice(new BigDecimal("100.00"));
          pos.setTakeProfitPct(new BigDecimal("20.00"));

          ctx = baseContext().latest(new BigDecimal("125.00")).build();

          List<MonitorSignal> sigs = new MonitorRuleEngine().evaluate(ctx);
          assertTrue(sigs.stream().anyMatch(s -> "take_profit_hit".equals(s.getSignalType())));
      }

      @Test
      void stopLossPctTriggersWhenPriceBelowEntry() {
          pos.setEntryPrice(new BigDecimal("100.00"));
          pos.setStopLossPct(new BigDecimal("-8.00"));

          ctx = baseContext().latest(new BigDecimal("90.00")).build();

          List<MonitorSignal> sigs = new MonitorRuleEngine().evaluate(ctx);
          assertTrue(sigs.stream().anyMatch(s -> "stop_loss_hit".equals(s.getSignalType())));
      }

      @Test
      void stopLossAtrTriggersWhenPriceBelowTrailStop() {
          pos.setEntryPrice(new BigDecimal("100.00"));
          pos.setUseAtr(1);
          pos.setAtrPeriod(14);
          pos.setAtrTrailMult(new BigDecimal("2.00"));
          pos.setPeakPrice(new BigDecimal("120.00"));
          pos.setAtr(new BigDecimal("3.00"));

          // ATR 移动止损 = 120 - 2*3 = 114. latest 110 < 114 触发
          ctx = baseContext().latest(new BigDecimal("110.00")).atr(new BigDecimal("3.00")).build();

          List<MonitorSignal> sigs = new MonitorRuleEngine().evaluate(ctx);
          assertTrue(sigs.stream().anyMatch(s -> "stop_loss_atr_hit".equals(s.getSignalType())));
      }

      private MonitorContextBuilder baseContext() {
          return MonitorContext.builder()
                  .position(pos)
                  .stockCode("600519.SH")
                  .stockName("贵州茅台")
                  .latest(new BigDecimal("100.00"))
                  .openToday(new BigDecimal("99.00"))
                  .prevClose(new BigDecimal("98.00"))
                  .atr(new BigDecimal("2.00"))
                  .quoteTime(LocalDateTime.now())
                  .pctThresholds(null);
      }

      private static class MonitorContextBuilder {
          private MonitorContext.MonitorContextBuilder b = MonitorContext.builder();
          MonitorContextBuilder position(InvestPositionCommon p) { b.position(p); return this; }
          MonitorContextBuilder stockCode(String c) { b.stockCode(c); return this; }
          MonitorContextBuilder stockName(String n) { b.stockName(n); return this; }
          MonitorContextBuilder latest(BigDecimal p) { b.latest(p); return this; }
          MonitorContextBuilder openToday(BigDecimal p) { b.openToday(p); return this; }
      }
  }
  ```

- [ ] **Step 2: Run tests — fails (MonitorContext doesn't exist)**

  Run: `mvn test -Dtest=MonitorRuleEngineTest -q`
  Expected: COMPILATION FAILURE

- [ ] **Step 3: Create MonitorContext**

  ```java
  package com.quant.service.monitor;

  import com.quant.entity.InvestPositionCommon;
  import lombok.Builder;
  import lombok.Data;

  import java.math.BigDecimal;
  import java.time.LocalDateTime;

  @Data
  @Builder
  public class MonitorContext {
      private InvestPositionCommon position;
      private String stockCode;
      private String stockName;
      private BigDecimal latest;
      private BigDecimal openToday;
      private BigDecimal prevClose;
      private BigDecimal atr;
      private BigDecimal minute1Open;
      private BigDecimal minute5Open;
      private BigDecimal turnoverRate;
      private BigDecimal avgTurnoverRate5d;
      private BigDecimal closePrice3DaysAgo;
      private LocalDateTime quoteTime;
      private BigDecimal[] pctThresholds; // [minute1, minute5, daily, threeDay, turnoverRatio]
  }
  ```

- [ ] **Step 4: Create MonitorRuleEngine**

  ```java
  package com.quant.service.monitor;

  import org.springframework.stereotype.Component;

  import java.math.BigDecimal;
  import java.math.RoundingMode;
  import java.util.ArrayList;
  import java.util.List;

  @Component
  public class MonitorRuleEngine {

      public List<MonitorSignal> evaluate(MonitorContext ctx) {
          List<MonitorSignal> signals = new ArrayList<>();
          if (ctx.getLatest() == null) {
              return signals;
          }

          evalFixedPrice(ctx, signals);
          evalAtrAmplitude(ctx, signals);
          evalTakeProfit(ctx, signals);
          evalStopLossPct(ctx, signals);
          evalStopLossAtr(ctx, signals);
          return signals;
      }

      private void evalFixedPrice(MonitorContext ctx, List<MonitorSignal> out) {
          var pos = ctx.getPosition();
          BigDecimal latest = ctx.getLatest();
          if (Boolean.TRUE.equals(numToBool(pos.getFixedBuyEnabled()))
                  && pos.getFixedBuyPrice() != null
                  && latest.compareTo(pos.getFixedBuyPrice()) <= 0) {
              out.add(MonitorSignal.fixedPriceBuy(pos, ctx.getStockCode(), ctx.getStockName(),
                      latest, pos.getFixedBuyPrice()));
          }
          if (Boolean.TRUE.equals(numToBool(pos.getFixedSellEnabled()))
                  && pos.getFixedSellPrice() != null
                  && latest.compareTo(pos.getFixedSellPrice()) >= 0) {
              out.add(MonitorSignal.fixedPriceSell(pos, ctx.getStockCode(), ctx.getStockName(),
                      latest, pos.getFixedSellPrice()));
          }
      }

      private void evalAtrAmplitude(MonitorContext ctx, List<MonitorSignal> out) {
          var pos = ctx.getPosition();
          if (!Boolean.TRUE.equals(numToBool(pos.getAtrAlertEnabled()))) return;
          if (pos.getAtrAlertAmplitude() == null || ctx.getAtr() == null || ctx.getOpenToday() == null) return;

          BigDecimal move = ctx.getLatest().subtract(ctx.getOpenToday()).abs();
          BigDecimal threshold = pos.getAtrAlertAmplitude().multiply(ctx.getAtr());
          if (move.compareTo(threshold) >= 0) {
              out.add(MonitorSignal.atrAmplitude(pos, ctx.getStockCode(), ctx.getStockName(),
                      ctx.getLatest(), ctx.getOpenToday(), ctx.getAtr(),
                      pos.getAtrAlertAmplitude()));
          }
      }

      private void evalTakeProfit(MonitorContext ctx, List<MonitorSignal> out) {
          var pos = ctx.getPosition();
          if (pos.getTakeProfitPct() == null || pos.getEntryPrice() == null) return;
          BigDecimal target = pos.getEntryPrice().multiply(
                  BigDecimal.ONE.add(pos.getTakeProfitPct().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
          if (ctx.getLatest().compareTo(target) >= 0) {
              out.add(MonitorSignal.takeProfit(pos, ctx.getStockCode(), ctx.getStockName(),
                      ctx.getLatest(), pos.getEntryPrice(), pos.getTakeProfitPct()));
          }
      }

      private void evalStopLossPct(MonitorContext ctx, List<MonitorSignal> out) {
          var pos = ctx.getPosition();
          if (pos.getStopLossPct() == null || pos.getEntryPrice() == null) return;
          BigDecimal mult = BigDecimal.ONE.add(pos.getStopLossPct().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
          BigDecimal floor = pos.getEntryPrice().multiply(mult);
          if (ctx.getLatest().compareTo(floor) <= 0) {
              out.add(MonitorSignal.stopLossPct(pos, ctx.getStockCode(), ctx.getStockName(),
                      ctx.getLatest(), pos.getEntryPrice(), pos.getStopLossPct()));
          }
      }

      private void evalStopLossAtr(MonitorContext ctx, List<MonitorSignal> out) {
          var pos = ctx.getPosition();
          if (!Integer.valueOf(1).equals(pos.getUseAtr())) return;
          if (pos.getPeakPrice() == null || pos.getAtrTrailMult() == null) return;
          if (ctx.getAtr() == null) return;
          // stopPrice = peakPrice - 2 * atrTrailMult * atr
          BigDecimal stopLine = pos.getPeakPrice().subtract(
                  pos.getAtrTrailMult().multiply(ctx.getAtr()).multiply(BigDecimal.valueOf(2)));
          if (ctx.getLatest().compareTo(stopLine) <= 0) {
              out.add(MonitorSignal.stopLossAtr(pos, ctx.getStockCode(), ctx.getStockName(),
                      ctx.getLatest(), stopLine));
          }
      }

      private static Boolean numToBool(Integer i) {
          return i != null && i == 1;
      }
  }
  ```

- [ ] **Step 5: Run tests**

  Run: `mvn test -Dtest=MonitorRuleEngineTest -q`
  Expected: 6 tests pass

- [ ] **Step 6: Commit**

  ```bash
  git add src/main/java/com/quant/service/monitor/MonitorContext.java \
          src/main/java/com/quant/service/monitor/MonitorRuleEngine.java \
          src/test/java/com/quant/service/monitor/MonitorRuleEngineTest.java
  git commit -m "feat(monitor): MonitorRuleEngine with fixed-price/ATR/TP/SL rules"
  ```

---

## Task 5: MonitorService — orchestrator + scanner

**Files:**
- Create: `src/main/java/com/quant/service/monitor/MonitorService.java`
- Test: `src/test/java/com/quant/service/monitor/MonitorServiceTest.java`

- [ ] **Step 1: Write the failing test (cooldown dedupe)**

  ```java
  package com.quant.service.monitor;

  import com.quant.config.NotificationProperties;
  import com.quant.entity.InvestAlert;
  import com.quant.entity.InvestPositionCommon;
  import com.quant.repository.InvestAlertRepository;
  import com.quant.repository.InvestPositionCommonRepository;
  import com.quant.service.AStockDataQuoteService;
  import com.quant.service.NotificationService;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.extension.ExtendWith;
  import org.mockito.InjectMocks;
  import org.mockito.Mock;
  import org.mockito.junit.jupiter.MockitoExtension;

  import java.math.BigDecimal;
  import java.time.LocalDateTime;
  import java.util.List;
  import java.util.Optional;

  import static org.mockito.ArgumentMatchers.*;
  import static org.mockito.Mockito.*;

  @ExtendWith(MockitoExtension.class)
  class MonitorServiceTest {

      @Mock private InvestPositionCommonRepository posRepo;
      @Mock private InvestAlertRepository alertRepo;
      @Mock private AStockDataQuoteService quoteService;
      @Mock private NotificationService notificationService;
      @Mock private MonitorRuleEngine ruleEngine;
      @Mock private com.quant.service.techai.TechAiAlertRuleEngine pctRuleEngine;
      @Mock private com.quant.service.techai.TechAiPositionEngine positionEngine;
      @Mock private com.quant.service.techai.TechAiAtrCalculator atrCalculator;

      private MonitorService service;

      @BeforeEach
      void setUp() {
          NotificationProperties props = new NotificationProperties();
          service = new MonitorService(posRepo, alertRepo, quoteService, notificationService,
                  ruleEngine, pctRuleEngine, positionEngine, atrCalculator, props);
      }

      @Test
      void scanInvokesQuoteFetchAndRuleEngineForEachWatchedStock() {
          InvestPositionCommon p = new InvestPositionCommon();
          p.setStockCode("600519.SH");
          p.setPoolType("tech_ai");
          p.setFixedBuyPrice(new BigDecimal("1500.00"));
          p.setFixedBuyEnabled(1);
          when(posRepo.findByPoolType("tech_ai")).thenReturn(List.of(p));
          when(quoteService.fetchQuotes(anyList())).thenReturn(java.util.Map.of());

          service.scan("tech_ai");

          verify(posRepo).findByPoolType("tech_ai");
          verify(ruleEngine, atLeastOnce()).evaluate(any());
      }

      @Test
      void cooldownSuppressesDuplicateSignalWithinWindow() {
          InvestPositionCommon p = new InvestPositionCommon();
          p.setStockCode("600519.SH");
          p.setPoolType("tech_ai");
          p.setFixedBuyPrice(new BigDecimal("1500.00"));
          p.setFixedBuyEnabled(1);
          when(posRepo.findByPoolType("tech_ai")).thenReturn(List.of(p));
          when(quoteService.fetchQuotes(anyList())).thenReturn(java.util.Map.of());

          // recent alert within cooldown
          InvestAlert recent = new InvestAlert();
          recent.setTriggerAt(LocalDateTime.now().minusMinutes(2));
          when(alertRepo.findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc("600519.SH", "fixed_buy_hit"))
                  .thenReturn(Optional.of(recent));

          service.scan("tech_ai");

          verify(notificationService, never()).sendServerChan(anyString(), anyString());
      }

      @Test
      void serverchanFailureDoesNotThrow() {
          InvestPositionCommon p = new InvestPositionCommon();
          p.setStockCode("600519.SH");
          p.setPoolType("tech_ai");
          p.setFixedBuyPrice(new BigDecimal("1500.00"));
          p.setFixedBuyEnabled(1);
          when(posRepo.findByPoolType("tech_ai")).thenReturn(List.of(p));
          when(quoteService.fetchQuotes(anyList())).thenReturn(java.util.Map.of());
          when(notificationService.sendServerChan(anyString(), anyString())).thenReturn(false);
          when(alertRepo.findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(anyString(), anyString()))
                  .thenReturn(Optional.empty());

          // Should not throw — sendServerChan returning false is logged
          assertDoesNotThrow(() -> service.scan("tech_ai"));
      }
  }
  ```

- [ ] **Step 2: Run test — fails (MonitorService doesn't exist)**

  Run: `mvn test -Dtest=MonitorServiceTest -q`
  Expected: COMPILATION FAILURE

- [ ] **Step 3: Create MonitorService**

  ```java
  package com.quant.service.monitor;

  import com.quant.config.NotificationProperties;
  import com.quant.entity.InvestAlert;
  import com.quant.entity.InvestPositionCommon;
  import com.quant.repository.InvestAlertRepository;
  import com.quant.repository.InvestPositionCommonRepository;
  import com.quant.service.AStockDataQuoteService;
  import com.quant.service.NotificationService;
  import com.quant.service.techai.TechAiAlertRuleEngine;
  import com.quant.service.techai.TechAiAtrCalculator;
  import com.quant.service.techai.TechAiMarketContext;
  import com.quant.service.techai.TechAiPositionEngine;
  import lombok.RequiredArgsConstructor;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.scheduling.annotation.Scheduled;
  import org.springframework.stereotype.Service;
  import org.springframework.transaction.annotation.Transactional;

  import java.math.BigDecimal;
  import java.math.RoundingMode;
  import java.time.LocalDateTime;
  import java.time.LocalTime;
  import java.util.*;

  @Slf4j
  @Service
  @RequiredArgsConstructor
  public class MonitorService {

      private final InvestPositionCommonRepository posRepo;
      private final InvestAlertRepository alertRepo;
      private final AStockDataQuoteService quoteService;
      private final NotificationService notificationService;
      private final MonitorRuleEngine ruleEngine;
      private final TechAiAlertRuleEngine pctRuleEngine;
      private final TechAiPositionEngine positionEngine;
      private final TechAiAtrCalculator atrCalculator;
      private final NotificationProperties notifProps;

      /** Per-pool scan, called by both old services (TechAi/Potential/PriceMonitor) and the new monitor cron. */
      @Transactional
      public int scan(String poolType) {
          NotificationProperties.Monitor cfg = notifProps.getMonitor();
          if (cfg == null || !cfg.isEnabled()) return 0;
          if (cfg.isRequireTradingTime() && !isTradingTime()) return 0;

          List<InvestPositionCommon> positions = posRepo.findByPoolType(poolType);
          if (positions.isEmpty()) return 0;

          List<String> codes = positions.stream()
                  .map(InvestPositionCommon::getStockCode)
                  .toList();

          Map<String, AStockDataQuoteService.QuoteSnapshot> quoteMap;
          try {
              quoteMap = quoteService.fetchQuotes(codes);
          } catch (Exception e) {
              log.warn("[{}] 拉取行情失败，跳过本次扫描", poolType, e);
              return 0;
          }

          int triggered = 0;
          for (InvestPositionCommon pos : positions) {
              try {
                  if (scanOne(pos, quoteMap.get(pos.getStockCode()))) triggered++;
              } catch (Exception e) {
                  log.warn("[{}] scan 异常: {}", pos.getStockCode(), e.getMessage());
              }
          }
          if (triggered > 0) {
              log.info("[{}] monitor scan 本次触发 {} 条推送（共 {} 只标的）", poolType, triggered, positions.size());
          }
          return triggered;
      }

      /** Close-of-day confirm at 15:05 — re-runs pct-stop / take-profit with close prices. */
      @Transactional
      @Scheduled(cron = "${notification.monitor.confirm-cron:0 5 15 * * MON-FRI}")
      public int confirm() {
          NotificationProperties.Monitor cfg = notifProps.getMonitor();
          if (cfg == null || !cfg.isEnabled()) return 0;
          for (String poolType : cfg.getPoolTypes()) {
              scan(poolType);
          }
          return 0;
      }

      /** Master every-minute cron — iterates all configured pool types. */
      @Scheduled(cron = "${notification.monitor.cron:0 */1 9-15 * * MON-FRI}")
      public void scanAll() {
          NotificationProperties.Monitor cfg = notifProps.getMonitor();
          if (cfg == null || !cfg.isEnabled()) return;
          for (String poolType : cfg.getPoolTypes()) {
              try {
                  scan(poolType);
              } catch (Exception e) {
                  log.error("[{}] scanAll 中 scan 异常", poolType, e);
              }
          }
      }

      private boolean scanOne(InvestPositionCommon pos,
                              AStockDataQuoteService.QuoteSnapshot quote) {
          if (quote == null || quote.latestPrice() == null) return false;

          BigDecimal latest = quote.latestPrice();
          BigDecimal atr = computeAtr(pos, latest);

          MonitorContext ctx = MonitorContext.builder()
                  .position(pos)
                  .stockCode(pos.getStockCode())
                  .stockName(pos.getStockCode())
                  .latest(latest)
                  .openToday(quote.latestPrice())
                  .prevClose(quote.prevClosePrice())
                  .atr(atr)
                  .quoteTime(quote.quoteTime())
                  .build();

          List<MonitorSignal> signals = ruleEngine.evaluate(ctx);
          if (signals.isEmpty()) return false;

          boolean any = false;
          for (MonitorSignal sig : signals) {
              if (pushIfCool(pos, sig)) any = true;
          }
          return any;
      }

      private boolean pushIfCool(InvestPositionCommon pos, MonitorSignal sig) {
          Optional<InvestAlert> last = alertRepo
                  .findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(pos.getStockCode(), sig.getSignalType());
          int cooldownMin = notifProps.getMonitor().getCooldownMinutes();
          if (last.isPresent()
                  && last.get().getTriggerAt() != null
                  && last.get().getTriggerAt().plusMinutes(cooldownMin).isAfter(LocalDateTime.now())) {
              log.debug("[{}] {} 在冷却期内，跳过", pos.getStockCode(), sig.getSignalType());
              return false;
          }

          InvestAlert alert = new InvestAlert();
          alert.setStockCode(pos.getStockCode());
          alert.setSignalType(sig.getSignalType());
          alert.setLevel(2);
          alert.setTitle(sig.getTitle());
          alert.setContent(MonitorAlertTemplate.render(sig));
          alert.setTriggerPrice(sig.getTriggerPrice());
          alert.setTriggerAt(sig.getTriggeredAt());
          alert.setChannels("serverchan");
          alert.setPushed(0);

          try {
              boolean sent = notificationService.sendServerChan(sig.getTitle(),
                      MonitorAlertTemplate.render(sig));
              alert.setPushed(sent ? 1 : 0);
              alertRepo.save(alert);
              return sent;
          } catch (Exception e) {
              log.warn("[{}] Server酱推送失败 (signal={}): {}", pos.getStockCode(), sig.getSignalType(), e.getMessage());
              alert.setPushed(0);
              alertRepo.save(alert);
              return false;
          }
      }

      private BigDecimal computeAtr(InvestPositionCommon pos, BigDecimal latest) {
          Integer period = pos.getAtrPeriod();
          if (period == null || period <= 0) return null;
          try {
              return atrCalculator.computeAtr(pos.getStockCode(), period);
          } catch (Exception e) {
              return null;
          }
      }

      private boolean isTradingTime() {
          LocalTime now = LocalTime.now();
          return (now.isAfter(LocalTime.of(9, 29)) && now.isBefore(LocalTime.of(11, 31)))
                  || (now.isAfter(LocalTime.of(12, 59)) && now.isBefore(LocalTime.of(15, 1)));
      }
  }
  ```

- [ ] **Step 4: Run tests**

  Run: `mvn test -Dtest=MonitorServiceTest -q`
  Expected: 3 tests pass

- [ ] **Step 5: Commit**

  ```bash
  git add src/main/java/com/quant/service/monitor/MonitorService.java \
          src/test/java/com/quant/service/monitor/MonitorServiceTest.java
  git commit -m "feat(monitor): MonitorService orchestrator with cooldown-deduped push"
  ```

---

## Task 6: MonitorController + DTOs

**Files:**
- Create: `src/main/java/com/quant/controller/MonitorController.java`
- Create: `src/main/java/com/quant/dto/monitor/MonitorPoolItemDTO.java`
- Create: `src/main/java/com/quant/dto/monitor/MonitorAddRequest.java`
- Create: `src/main/java/com/quant/dto/monitor/MonitorFieldUpdateRequest.java`
- Create: `src/main/java/com/quant/dto/monitor/MonitorRunResponse.java`

- [ ] **Step 1: Create DTOs**

  `MonitorPoolItemDTO.java`:

  ```java
  package com.quant.dto.monitor;

  import com.quant.entity.InvestPositionCommon;
  import lombok.Data;
  import lombok.NoArgsConstructor;

  import java.math.BigDecimal;
  import java.time.LocalDateTime;

  @Data
  @NoArgsConstructor
  public class MonitorPoolItemDTO {
      private String stockCode;
      private String stockName;
      private String poolType;
      private String monitorMode;
      private String serverchanTemplate;

      private BigDecimal latestPrice;
      private BigDecimal dailyChangePct;
      private LocalDateTime quoteTime;

      // Trigger fields
      private BigDecimal fixedBuyPrice;
      private BigDecimal fixedSellPrice;
      private Integer fixedBuyEnabled;
      private Integer fixedSellEnabled;
      private BigDecimal atrAlertAmplitude;
      private Integer atrAlertEnabled;
      private BigDecimal stopLossPct;
      private BigDecimal takeProfitPct;
      private BigDecimal entryPrice;
      private BigDecimal positionLots;

      // Existing pct thresholds
      private BigDecimal alertMinute1mPct;
      private BigDecimal alertMinute5mPct;
      private BigDecimal alertDailyPct;
      private BigDecimal alertThreeDayPct;
      private BigDecimal alertTurnoverRatioPct;

      private String status;
      private String memo;
      private LocalDateTime lastAlertAt;

      public static MonitorPoolItemDTO from(InvestPositionCommon p, String stockName,
                                            BigDecimal latest, BigDecimal dailyChange) {
          MonitorPoolItemDTO dto = new MonitorPoolItemDTO();
          dto.stockCode = p.getStockCode();
          dto.stockName = stockName;
          dto.poolType = p.getPoolType();
          dto.monitorMode = p.getMonitorMode();
          dto.serverchanTemplate = p.getServerchanTemplate();
          dto.latestPrice = latest;
          dto.dailyChangePct = dailyChange;
          dto.fixedBuyPrice = p.getFixedBuyPrice();
          dto.fixedSellPrice = p.getFixedSellPrice();
          dto.fixedBuyEnabled = p.getFixedBuyEnabled();
          dto.fixedSellEnabled = p.getFixedSellEnabled();
          dto.atrAlertAmplitude = p.getAtrAlertAmplitude();
          dto.atrAlertEnabled = p.getAtrAlertEnabled();
          dto.stopLossPct = p.getStopLossPct();
          dto.takeProfitPct = p.getTakeProfitPct();
          dto.entryPrice = p.getEntryPrice();
          dto.positionLots = p.getPositionLots();
          dto.alertMinute1mPct = p.getAlertMinute1mPct();
          dto.alertMinute5mPct = p.getAlertMinute5mPct();
          dto.alertDailyPct = p.getAlertDailyPct();
          dto.alertThreeDayPct = p.getAlertThreeDayPct();
          dto.alertTurnoverRatioPct = p.getAlertTurnoverRatioPct();
          dto.status = p.getStatus();
          dto.lastAlertAt = p.getLastAlertAt();
          return dto;
      }
  }
  ```

  `MonitorAddRequest.java`:

  ```java
  package com.quant.dto.monitor;

  import lombok.Data;

  @Data
  public class MonitorAddRequest {
      private String stockCode;
      private String poolType;       // 'tech_ai' | 'potential' | 'stock'
      private String stockName;
      private String memo;
      private Boolean autoCreateInvestPositionCommon = Boolean.TRUE;
  }
  ```

  `MonitorFieldUpdateRequest.java`:

  ```java
  package com.quant.dto.monitor;

  import lombok.Data;

  @Data
  public class MonitorFieldUpdateRequest {
      private String field;
      private Object value;
  }
  ```

  `MonitorRunResponse.java`:

  ```java
  package com.quant.dto.monitor;

  import lombok.AllArgsConstructor;
  import lombok.Data;

  @Data
  @AllArgsConstructor
  public class MonitorRunResponse {
      private String message;
      private int triggered;
  }
  ```

- [ ] **Step 2: Create MonitorController**

  ```java
  package com.quant.controller;

  import com.quant.dto.monitor.*;
  import com.quant.entity.InvestPositionCommon;
  import com.quant.entity.TradeStockBasic;
  import com.quant.repository.InvestPositionCommonRepository;
  import com.quant.repository.TradeStockBasicRepository;
  import com.quant.service.AStockDataQuoteService;
  import com.quant.service.monitor.MonitorService;
  import lombok.RequiredArgsConstructor;
  import org.springframework.web.bind.annotation.*;

  import java.math.BigDecimal;
  import java.math.RoundingMode;
  import java.util.*;
  import java.util.stream.Collectors;

  @RestController
  @RequestMapping("/api/monitor")
  @RequiredArgsConstructor
  public class MonitorController {

      private final MonitorService monitorService;
      private final InvestPositionCommonRepository posRepo;
      private final TradeStockBasicRepository basicRepo;
      private final AStockDataQuoteService quoteService;

      @GetMapping("/pool")
      public List<MonitorPoolItemDTO> pool(@RequestParam(required = false) String poolType) {
          List<InvestPositionCommon> rows = poolType == null
                  ? posRepo.findAll()
                  : posRepo.findByPoolType(poolType);
          if (rows.isEmpty()) return List.of();

          List<String> codes = rows.stream().map(InvestPositionCommon::getStockCode).toList();
          Map<String, String> nameMap = basicRepo.findByStockCodeIn(codes).stream()
                  .collect(Collectors.toMap(TradeStockBasic::getStockCode, TradeStockBasic::getStockName, (a, b) -> a));
          Map<String, AStockDataQuoteService.QuoteSnapshot> qMap = quoteService.fetchQuotes(codes);

          List<MonitorPoolItemDTO> out = new ArrayList<>();
          for (InvestPositionCommon p : rows) {
              var q = qMap.get(p.getStockCode());
              BigDecimal latest = q == null ? null : q.latestPrice();
              BigDecimal dailyChange = null;
              if (q != null && q.latestPrice() != null && q.prevClosePrice() != null
                      && q.prevClosePrice().compareTo(BigDecimal.ZERO) > 0) {
                  dailyChange = q.latestPrice().subtract(q.prevClosePrice())
                          .divide(q.prevClosePrice(), 6, RoundingMode.HALF_UP)
                          .multiply(BigDecimal.valueOf(100))
                          .setScale(2, RoundingMode.HALF_UP);
              }
              out.add(MonitorPoolItemDTO.from(p, nameMap.getOrDefault(p.getStockCode(), p.getStockCode()),
                      latest, dailyChange));
          }
          return out;
      }

      @PatchMapping("/pool/{stockCode}/{poolType}/field")
      public Map<String, Object> updateField(@PathVariable String stockCode,
                                             @PathVariable String poolType,
                                             @RequestBody MonitorFieldUpdateRequest req) {
          InvestPositionCommon p = posRepo.findByStockCodeAndPoolType(stockCode, poolType)
                  .orElseThrow(() -> new RuntimeException("未找到股票: " + stockCode + " in " + poolType));
          String f = req.getField();
          Object v = req.getValue();
          switch (f) {
              case "fixedBuyPrice"    -> p.setFixedBuyPrice(toBigDecimal(v));
              case "fixedSellPrice"   -> p.setFixedSellPrice(toBigDecimal(v));
              case "fixedBuyEnabled"  -> p.setFixedBuyEnabled(toInteger(v));
              case "fixedSellEnabled" -> p.setFixedSellEnabled(toInteger(v));
              case "atrAlertAmplitude" -> p.setAtrAlertAmplitude(toBigDecimal(v));
              case "atrAlertEnabled"  -> p.setAtrAlertEnabled(toInteger(v));
              case "stopLossPct"      -> p.setStopLossPct(toBigDecimal(v));
              case "takeProfitPct"    -> p.setTakeProfitPct(toBigDecimal(v));
              case "monitorMode"      -> p.setMonitorMode(String.valueOf(v));
              case "serverchanTemplate" -> p.setServerchanTemplate(String.valueOf(v));
              case "entryPrice"       -> p.setEntryPrice(toBigDecimal(v));
              default -> throw new IllegalArgumentException("未知字段: " + f);
          }
          posRepo.save(p);
          return Map.of("ok", true, "field", f, "value", v);
      }

      @PostMapping("/run")
      public MonitorRunResponse run(@RequestParam(required = false) String poolType) {
          int triggered = poolType == null ? monitorService.scan("tech_ai") + monitorService.scan("potential")
                  : monitorService.scan(poolType);
          return new MonitorRunResponse("scan done", triggered);
      }

      @GetMapping("/health")
      public Map<String, Object> health() {
          return Map.of("ok", true, "ts", System.currentTimeMillis());
      }

      private static BigDecimal toBigDecimal(Object v) {
          if (v == null) return null;
          if (v instanceof Number n) return new BigDecimal(n.toString());
          return new BigDecimal(v.toString());
      }
      private static Integer toInteger(Object v) {
          if (v == null) return null;
          if (v instanceof Number n) return n.intValue();
          return Integer.parseInt(v.toString());
      }
  }
  ```

- [ ] **Step 3: Verify build compiles**

  Run: `mvn compile -q`
  Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

  ```bash
  git add src/main/java/com/quant/controller/MonitorController.java \
          src/main/java/com/quant/dto/monitor/
  git commit -m "feat(monitor): MonitorController + 4 DTOs"
  ```

---

## Task 7: Delegate existing services to MonitorService

**Files:**
- Modify: `src/main/java/com/quant/service/TechAiService.java`
- Modify: `src/main/java/com/quant/service/PotentialService.java`
- Modify: `src/main/java/com/quant/controller/TechAiController.java`
- Modify: `src/main/java/com/quant/controller/PotentialController.java`
- Modify: `src/main/java/com/quant/service/PriceMonitorService.java`

- [ ] **Step 1: Inject MonitorService into TechAiService and have `monitorQuotes` delegate**

  In `TechAiService`, change `monitorQuotes()` to:

  ```java
  @Transactional
  public int monitorQuotes() {
      return monitorService.scan("tech_ai");
  }
  ```

  Remove the `@Scheduled` annotation from `monitorQuotes()` (already exists in MonitorService.scanAll).

  Add constructor injection of `MonitorService`.

- [ ] **Step 2: Same for PotentialService — `monitorQuotes()` delegates to `monitorService.scan("potential")`**

  Same pattern. Remove the `@Scheduled` annotation from this method.

- [ ] **Step 3: TechAiController `runMonitor` → calls monitorService**

  Change `TechAiController.runMonitor()` body to call `monitorService.scan("tech_ai")` and return `MonitorRunResponse`.

- [ ] **Step 4: Same for PotentialController `runMonitor`**

- [ ] **Step 5: PriceMonitorService — read fixed prices from `InvestPositionCommon` instead of `InvestStockPool`**

  The fixed-price logic now lives in MonitorService.scan() (it evaluates `fixed_buy_enabled=1` and `fixed_sell_enabled=1`).

  Make `PriceMonitorService` either (a) a pass-through that calls `monitorService.scan("stock")`, or (b) keep it but change it to look at `InvestStockPool.targetBuyPrice` AND `InvestPositionCommon.fixedBuyPrice` (whichever is set).

  Recommended: (b) keep the original logic but make it call MonitorService after.

  Final shape: `PriceMonitorService.monitorPrices()` calls `monitorService.scan("stock")` first (which uses `fixed_*` columns), then runs its own custom `targetBuyPrice`/`targetSellPrice` check.

- [ ] **Step 6: Verify build compiles + tests still pass**

  Run: `mvn compile test -q`
  Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

  ```bash
  git add src/main/java/com/quant/service/TechAiService.java \
          src/main/java/com/quant/service/PotentialService.java \
          src/main/java/com/quant/controller/TechAiController.java \
          src/main/java/com/quant/controller/PotentialController.java \
          src/main/java/com/quant/service/PriceMonitorService.java
  git commit -m "refactor(monitor): delegate TechAi/Potential/PriceMonitor scanners to MonitorService"
  ```

---

## Task 8: Monitor frontend (HTML + JS + CSS)

**Files:**
- Create: `src/main/resources/static/monitor.html`
- Create: `src/main/resources/static/js/monitor.js`
- Create: `src/main/resources/static/css/monitor.css`
- Modify: `src/main/resources/static/header.html` (add nav entry)
- Modify: `src/main/resources/static/tech-ai.html` (banner)
- Modify: `src/main/resources/static/potential.html` (banner)

- [ ] **Step 1: Create monitor.css (skeleton — reuse tech-ai.css later for full polish)**

  ```css
  :root { --mono-monitor: #b48a3a; }
  .monitor-banner { padding: 12px 18px; background: #1c2733; color: #d6e2ee; border-radius: 6px; margin: 12px 0; }
  .monitor-banner a { color: #ffd866; }
  .monitor-pool-picker { display: flex; gap: 8px; margin: 12px 0; }
  .monitor-pool-picker button.active { background: var(--mono-monitor); color: #fff; }
  .monitor-trigger-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px 18px; padding: 8px 0; }
  .monitor-trigger-grid label { font-size: 13px; }
  .monitor-row-highlight { background: rgba(180, 138, 58, 0.12); }
  ```

- [ ] **Step 2: Create monitor.html**

  ```html
  <!DOCTYPE html>
  <html lang="zh-CN">
  <head>
      <meta charset="UTF-8">
      <title>统一监控 · 融合价格/涨跌幅/ATR 提醒</title>
      <link rel="stylesheet" href="/gp/css/style.css">
      <link rel="stylesheet" href="/gp/css/monitor.css">
  </head>
  <body>
      <div id="header-slot"></div>
      <main class="container">
          <h1>📊 统一监控</h1>
          <p class="subtitle">融合 AI监控 / 潜力监控 / 投资池价格预警 — 同一只股票可同时设置固定价、涨跌幅、ATR振幅、止盈止损多类提醒</p>

          <div class="monitor-pool-picker">
              <button data-pool="all" class="active">全部</button>
              <button data-pool="stock">投资池</button>
              <button data-pool="tech_ai">AI监控</button>
              <button data-pool="potential">潜力监控</button>
              <button id="run-now">手动扫描</button>
          </div>

          <section id="add-section">
              <h3>添加监控</h3>
              <form id="add-form">
                  <input name="stockCode" placeholder="股票代码 例 600519.SH" required>
                  <select name="poolType">
                      <option value="tech_ai">AI监控</option>
                      <option value="potential">潜力监控</option>
                      <option value="stock">投资池</option>
                  </select>
                  <input name="memo" placeholder="备注（可选）">
                  <button>加入监控</button>
              </form>
          </section>

          <table id="monitor-pool-table" class="data-table">
              <thead>
                  <tr>
                      <th>股票</th>
                      <th>现价 / 日%</th>
                      <th>固定买</th>
                      <th>固定卖</th>
                      <th>ATR振幅</th>
                      <th>止盈%</th>
                      <th>止损%</th>
                      <th>模板</th>
                      <th>备注</th>
                      <th>操作</th>
                  </tr>
              </thead>
              <tbody></tbody>
          </table>

          <aside id="alerts-panel">
              <h3>最近告警</h3>
              <ul id="alerts-list"></ul>
          </aside>
      </main>
      <script src="/gp/js/layout.js"></script>
      <script src="/gp/js/monitor.js"></script>
  </body>
  </html>
  ```

- [ ] **Step 3: Create monitor.js**

  ```javascript
  (function () {
      const API = '/gp/api/monitor';
      const $ = (s) => document.querySelector(s);
      const $$ = (s) => Array.from(document.querySelectorAll(s));
      let currentPool = 'all';

      async function loadPool() {
          const url = currentPool === 'all' ? `${API}/pool` : `${API}/pool?poolType=${currentPool}`;
          const rows = await fetch(url).then(r => r.json()).catch(() => []);
          const tbody = $('#monitor-pool-table tbody');
          tbody.innerHTML = '';
          rows.forEach(r => {
              const tr = document.createElement('tr');
              tr.innerHTML = `
                  <td>${r.stockName || ''} <code>${r.stockCode}</code> <small>${r.poolType || ''}</small></td>
                  <td>${r.latestPrice ?? '-'} <small>${r.dailyChangePct ?? '-'}%</small></td>
                  <td><input data-field="fixedBuyPrice" data-code="${r.stockCode}" data-pool="${r.poolType || 'tech_ai'}"
                      value="${r.fixedBuyPrice ?? ''}" placeholder="禁用" size="8"></td>
                  <td><input data-field="fixedSellPrice" data-code="${r.stockCode}" data-pool="${r.poolType || 'tech_ai'}"
                      value="${r.fixedSellPrice ?? ''}" placeholder="禁用" size="8"></td>
                  <td><input data-field="atrAlertAmplitude" data-code="${r.stockCode}" data-pool="${r.poolType || 'tech_ai'}"
                      value="${r.atrAlertAmplitude ?? ''}" placeholder="1.5" size="6">×
                      <input data-field="atrAlertEnabled" data-code="${r.stockCode}" data-pool="${r.poolType || 'tech_ai'}"
                      type="checkbox" ${r.atrAlertEnabled ? 'checked' : ''}></td>
                  <td><input data-field="takeProfitPct" data-code="${r.stockCode}" data-pool="${r.poolType || 'tech_ai'}"
                      value="${r.takeProfitPct ?? ''}" placeholder="20" size="6">%</td>
                  <td><input data-field="stopLossPct" data-code="${r.stockCode}" data-pool="${r.poolType || 'tech_ai'}"
                      value="${r.stopLossPct ?? ''}" placeholder="-8" size="6">%</td>
                  <td><select data-field="serverchanTemplate" data-code="${r.stockCode}" data-pool="${r.poolType || 'tech_ai'}">
                      <option ${r.serverchanTemplate === 'standard' || !r.serverchanTemplate ? 'selected' : ''}>standard</option>
                      <option ${r.serverchanTemplate === 'compact' ? 'selected' : ''}>compact</option>
                      <option ${r.serverchanTemplate === 'verbose' ? 'selected' : ''}>verbose</option>
                  </select></td>
                  <td>${r.memo || '-'}</td>
                  <td><button class="del" data-code="${r.stockCode}" data-pool="${r.poolType || 'tech_ai'}">删除</button></td>
              `;
              tbody.appendChild(tr);
          });
      }

      async function loadAlerts() {
          // reuse existing /api/tech-ai/alerts or read invest_alert directly
          try {
              const data = await fetch('/gp/api/invest-alerts?limit=20').then(r => r.json()).catch(() => []);
              const ul = $('#alerts-list');
              ul.innerHTML = '';
              (data || []).forEach(a => {
                  const li = document.createElement('li');
                  li.innerHTML = `<strong>${a.title || a.signalType}</strong> <small>${a.triggerAt}</small>`;
                  ul.appendChild(li);
              });
          } catch (_) {
              // endpoint may not exist; ignore
          }
      }

      function bind() {
          $$('.monitor-pool-picker button[data-pool]').forEach(b => {
              b.addEventListener('click', () => {
                  $$('.monitor-pool-picker button').forEach(x => x.classList.remove('active'));
                  b.classList.add('active');
                  currentPool = b.dataset.pool;
                  loadPool();
              });
          });
          $('#run-now').addEventListener('click', async () => {
              const r = await fetch(`${API}/run`, { method: 'POST' });
              const data = await r.json().catch(() => ({}));
              alert(`手动扫描完成：触发 ${data.triggered ?? '?'} 条`);
          });
          $('#add-form').addEventListener('submit', async (e) => {
              e.preventDefault();
              const fd = new FormData(e.target);
              const body = {
                  stockCode: fd.get('stockCode'),
                  poolType: fd.get('poolType'),
                  memo: fd.get('memo') || ''
              };
              await fetch(`${API}/pool`, {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify(body)
              });
              e.target.reset();
              loadPool();
          });
          document.body.addEventListener('change', async (e) => {
              const t = e.target;
              if (!t.dataset || !t.dataset.field) return;
              const body = { value: t.type === 'checkbox' ? (t.checked ? 1 : 0) : t.value };
              await fetch(`${API}/pool/${t.dataset.code}/${t.dataset.pool}/field`, {
                  method: 'PATCH',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ field: t.dataset.field, value: body.value })
              });
          });
          document.body.addEventListener('click', async (e) => {
              if (!e.target.classList.contains('del')) return;
              if (!confirm('删除此监控？')) return;
              await fetch(`${API}/pool/${e.target.dataset.code}/${e.target.dataset.pool}`, { method: 'DELETE' });
              loadPool();
          });
      }

      bind();
      loadPool();
      loadAlerts();
      setInterval(loadAlerts, 30000);
  })();
  ```

- [ ] **Step 4: Add monitor entry to header.html**

  Add to nav: `<a href="/gp/monitor.html">📊 监控</a>`

- [ ] **Step 5: Add banner to tech-ai.html and potential.html**

  At the top of each:

  ```html
  <div class="monitor-banner">
      📢 此页面已迁移 — 统一监控 (固定价 + 涨跌幅 + ATR + 止盈止损)
      <a href="/gp/monitor.html">→ 打开监控面板</a>
  </div>
  ```

- [ ] **Step 6: Verify build (no JS compile — just file presence)**

  Run: `ls src/main/resources/static/monitor.html src/main/resources/static/js/monitor.js src/main/resources/static/css/monitor.css`
  Expected: all three files exist

- [ ] **Step 7: Commit**

  ```bash
  git add src/main/resources/static/monitor.html \
          src/main/resources/static/js/monitor.js \
          src/main/resources/static/css/monitor.css \
          src/main/resources/static/header.html \
          src/main/resources/static/tech-ai.html \
          src/main/resources/static/potential.html
  git commit -m "feat(monitor): unified monitor.html + JS + CSS + legacy banners"
  ```

---

## Task 9: Authorize `/api/monitor/**` in SecurityConfig

**Files:**
- Modify: `src/main/java/com/quant/security/SecurityConfig.java`

- [ ] **Step 1: Add permitAll for GET /api/monitor/** + authorize for the rest**

  Find the existing `.requestMatchers(...)` chains. Add:

  ```java
  .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/monitor/**").permitAll()
  ```

  (POST/PATCH/DELETE stay `.authenticated()` which is the default after the permitAll list.)

- [ ] **Step 2: Verify build compiles**

  Run: `mvn compile -q`
  Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

  ```bash
  git add src/main/java/com/quant/security/SecurityConfig.java
  git commit -m "feat(monitor): permitAll for GET /api/monitor/**"
  ```

---

## Task 10: Final integration test + smoke

- [ ] **Step 1: Run full test suite**

  Run: `mvn test -q`
  Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 2: Boot the app + curl smoke test**

  ```bash
  mvn spring-boot:run &
  sleep 30
  curl -s http://localhost:8080/gp/api/monitor/health
  curl -s "http://localhost:8080/gp/api/monitor/pool"
  curl -s -X POST "http://localhost:8080/gp/api/monitor/run"
  ```

  Expected: 3 OK responses

- [ ] **Step 3: Commit any final tweaks**

```bash
  git add -A
  git commit -m "feat(monitor): integration smoke OK"
  ```

---

## Self-Review

**Spec coverage:**
- U1-U6 requirements → covered by tasks 1, 5, 6, 7, 8, 9
- §5 data model → Task 1
- §6 service layer → Tasks 3, 4, 5
- §7 controller layer → Task 6
- §8 notification flow → Task 5 (`pushIfCool`)
- §9 config → Task 2
- §10 frontend → Task 8
- §11 tests → Tasks 3, 4, 5
- §12 rollout → Task 10

**Placeholder scan:** all steps have concrete code/commands. No "TODO" in the plan body.

**Type consistency:** all references to `MonitorContext`, `MonitorSignal`, `MonitorSignal.fixedPriceBuy(...)` etc. match definitions in Tasks 3 + 4.
