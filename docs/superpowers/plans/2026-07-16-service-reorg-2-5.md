# Sprint 2.5 — service/ Package Reorganization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans.

**Goal:** Move ~40 `service/*.java` files into domain-specific sub-packages. Goal: no more than 12 files at the root of `service/`. Each domain sub-package owns all related logic.

**Architecture:**

```
service/
├── aistockdata/        — 实时行情三家 + Baostock sync
│   ├── BaostockSyncCoordinator.java
│   ├── BaostockSyncService.java
│   ├── BaostockMinuteQuoteService.java
│   ├── AStockDataForecastProvider.java
│   ├── AStockDataQuoteService.java
│   ├── EastMoneyRealtimeQuoteService.java
│   ├── SinaRealtimeQuoteService.java
│   ├── QuoteHttpClient.java
│   └── QuoteService.java
├── ai/                 — AI 已存在，扩展
│   ├── NotificationDispatcher.java   (从 root 移入)
│   ├── WishPoolService.java
│   ├── WishPoolNotifier.java
│   └── (existing)
├── invest/             — 龙江投资 + 池管理
│   ├── InvestService.java
│   ├── InvestPoolMetaService.java
│   ├── InvestPoolRefreshService.java
│   ├── InvestPoolSeedService.java
│   ├── InvestBigYangSignalService.java
│   ├── InvestBigYangSignalScheduler.java
│   ├── InvestForecastProvider.java
│   ├── InvestWeeklyOpportunityService.java
│   ├── OcrPoolImportService.java
│   └── (some already in xieboinvest/)
├── recap/              — 每日复盘 / 资金流向
│   ├── DailyRecapService.java
│   ├── MarketRecapService.java
│   └── FundFlowService.java
├── study/              — 学习搭子
│   ├── StudyService.java
│   └── StudyUploadService.java
├── potential/          — 实战选股 + 潜力池
│   ├── PracticalSelectService.java
│   ├── PracticalSelectPdfService.java
│   ├── PotentialService.java
│   └── UnifiedStockResearchService.java
├── notification/       — 通知基础设施
│   ├── NotificationService.java
│   ├── PriceMonitorService.java
│   └── JournalService (relocated from journal sub-package)
├── auth/               — auth helpers (after Sprint 2.3 will be small)
│   └── AuthService.java
├── search/             — already exists, keep
├── journal/            — already exists, keep
├── monitor/            — already exists, keep
├── position/           — already exists, keep
├── prosperitystrong/   — already exists (Sprint 2.4 will fill it more)
├── stockanalysis/      — already exists (Sprint 2.4 will fill it more)
├── techai/             — already exists, keep
├── tdx/                — already exists, keep
├── wechat/             — already exists, keep
├── xieboinvest/        — already exists, keep
└── EmailService.java + SmsService.java + StatsService.java + StockQueryService.java + Service*.java — keep at root (cross-cutting)
```

**Tech Stack:** Same as project. Mechanical import-fixing, no new deps.

---

## ⚠️ Hard constraints

1. **No behavior changes** — purely structural.
2. **Each file move is its own commit** for clean git log.
3. **Imports in callers must be updated** — controllers, other services, scheduled jobs.
4. **Test classes need same path adjustment** in `src/test/java`.

---

## Task 1: Create new sub-package directories

Just `mkdir -p` for each of the new packages. No Java files yet.

```bash
mkdir -p src/main/java/com/quant/service/{aistockdata,recap,study,potential,notification,invest}
```

(ai/, prosperitystrong/, stockanalysis/, etc. already exist.)

Commit: `chore(service): scaffold new sub-package directories`

---

## Task 2: Move quote + baostock cluster → aistockdata/

Files to move (9 files):
- `BaostockSyncCoordinator.java`
- `BaostockSyncService.java`
- `BaostockMinuteQuoteService.java`
- `AStockDataForecastProvider.java`
- `AStockDataQuoteService.java`
- `EastMoneyRealtimeQuoteService.java`
- `SinaRealtimeQuoteService.java`
- `QuoteHttpClient.java`
- `QuoteService.java`

For each:
1. `git mv` to `src/main/java/com/quant/service/aistockdata/`
2. Update `package com.quant.service;` → `package com.quant.service.aistockdata;`
3. Update all internal imports referencing other files in this group
4. Update callers in `controller/`, `scheduled jobs/`, etc.
5. `mvn compile -q` to confirm

One commit per file or one commit for the cluster — pick "one commit per cluster" for speed.

---

## Task 3: Move recap cluster → recap/

Files: `DailyRecapService.java`, `MarketRecapService.java`, `FundFlowService.java`.

Same per-file pattern.

---

## Task 4: Move study cluster → study/

Files: `StudyService.java`, `StudyUploadService.java`.

---

## Task 5: Move invest cluster → invest/

Files: 8+ files (see plan table). Many already in `xieboinvest/` sub-package — those don't move.

This is the largest move (most files, most cross-cutting imports). Budget 1-2 hours.

After Sprint 2.4 finishes, the invest cluster will have ~12 services (InvestService + 4 helpers). Move them all in this task.

---

## Task 6: Move potential cluster → potential/

Files: 4 files. Split dependencies may surface — PracticalSelectService probably calls StockAnalysisService, etc.

---

## Task 7: Move notification cluster → notification/ + extend ai/

Files: NotificationDispatcher, NotificationService, PriceMonitorService → `notification/`
WishPoolService, WishPoolNotifier → `ai/` (per audit doc)

---

## Task 8: Final cleanup — verify + commit

- `find src/main/java/com/quant/service/*.java | wc -l` should be < 12 (just cross-cutting services)
- `mvn compile -DskipTests` clean
- `mvn spotless:check` clean

---

## Per-file move pattern

```bash
# 1. Move file
git mv src/main/java/com/quant/service/OldService.java src/main/java/com/quant/service/newpkg/OldService.java

# 2. Update package + imports in file
# (use Edit tool to replace 'package com.quant.service;' → 'package com.quant.service.newpkg;')

# 3. Update callers (other files that import com.quant.service.OldService)
# (use grep to find callers, Edit each)

# 4. Compile-check
mvn compile -q -DskipTests

# 5. Commit
git add src/main/java/com/quant/service/newpkg/ src/main/java/com/quant/<other modified callers>
git commit -m "refactor(service): move OldService to newpkg/ + update callers"
```

---

## Self-review

- [ ] All target files in new packages
- [ ] All callers updated (no broken imports)
- [ ] `mvn compile` clean
- [ ] `mvn spotless:check` clean
- [ ] Cross-cutting services (auth, email, sms, stats, stockquery) still at root
- [ ] Each commit is clean (no .orig, no leftover imports)

---

## Out of scope

- Sub-package internals — they remain in `service/` root after this plan lands, then a follow-up plan moves them deeper if needed.
- Renaming files (just moving)
- Combining files (purely structural — if two files are duplicative, that's a separate plan)
