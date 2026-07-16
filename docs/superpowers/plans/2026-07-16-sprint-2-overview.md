# Sprint 2 — Architecture & Maintainability Overview

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lift `gupiao-quant` from "monolithic scripts + ad-hoc ALTERs" to a maintainable, versioned, linted codebase. Five sub-projects, prioritized P0 → P2. Each gets its own plan file under `docs/superpowers/plans/`.

**Architecture across the sprint:**
- **Migrations** → Flyway versioned SQL under `src/main/resources/db/migration/V{n}__{name}.sql`. `SchemaInitializer` becomes a thin legacy-fallback bean, gated on a property. Existing `sql/*.sql` files move into V-numbered naming.
- **Controllers** → split by responsibility (auth/login/admin/audit). One cohesive resource per controller, < 300 lines.
- **Static analysis** → Spotless (formatting) + Checkstyle (style) + SpotBugs (defects) wired to `mvn verify`. `System.out` and `printStackTrace` are flagged.
- **God-class splits** → split largest `service/*.java` files (ProsperityPickService, StockAnalysisService first) into focused beans. Each new bean < 300 lines, single responsibility, narrow public surface.
- **Package reorganization** → bulk move of `service/*.java` into sub-packages by domain. Mechanical import-fixing.

**Tech Stack:** Spring Boot 3.2.5, Flyway 9.x (`flyway-core` + `flyway-mysql`), Spotless 2.x (google-java-format), Checkstyle 10.x (Google Java Style), SpotBugs 4.x.

---

## Sub-projects (in recommended order)

### 🔴 2.1 Flyway migration (P0 — start here)

Plan file: `docs/superpowers/plans/2026-07-16-flyway-migration-2-1.md`

Convert 25 ad-hoc `sql/*.sql` files into Flyway versioned migrations. Replace `SchemaInitializer.java` (1250 lines) with a thin legacy-fallback bean. Establishes the schema-version contract; all later sub-projects depend on this.

**Risk:** Current prod DB schema state is unknown. Use Flyway's `baseline-on-migrate=true` for the production cutover. Dev environments get a clean start.

**ETA:** 2-3 days.

### 🟠 2.2 Static analysis wiring (P1)

Plan file (TBD). Adds Spotless + Checkstyle + SpotBugs to `pom.xml`, wires them to `mvn verify`. Fixes 5 `printStackTrace` sites and any `System.out` calls. After this lands, `mvn verify` blocks future regressions.

**ETA:** 0.5 day.

### 🟠 2.3 AuthController split (P1)

Plan file (TBD). 672-line `AuthController` → 4-5 controllers: registration / password-login / sms-login / wechat-login / admin. Plus audit log endpoints.

**ETA:** 1 day.

### 🟠 2.4 God-class splits (P1)

Plan file (TBD). Targets, in order:
1. `ProsperityPickService` (1191 lines) — split per candidate-generation phase
2. `StockAnalysisService` (1173 lines) — split per dimension (financial / sentiment / technical)
3. `PracticalSelectService` (1142) — same shape
4. `PotentialService` (1105) — same shape
5. `UnifiedStockResearchService` (1102) — orchestration layer
6. `InvestService` (855) — split pool / position / valuation
7. `AuthService` (not counted in 600+, but adjacent)

**ETA:** 2-3 days, one at a time, each is its own plan.

### 🟡 2.5 service/ package reorganization (P2)

Plan file (TBD). Bulk move `service/*.java` (~40 files) into sub-packages by domain:
- `invest/` — InvestService, InvestPoolMetaService, InvestPoolRefreshService, InvestPoolSeedService, InvestBigYangSignalService, InvestBigYangSignalScheduler, InvestForecastProvider, OcrPoolImportService, xieboinvest/* (already sub-packaged)
- `quote/` — BaostockSyncCoordinator, BaostockSyncService, BaostockMinuteQuoteService, AStockDataForecastProvider, AStockDataQuoteService, EastMoneyRealtimeQuoteService, SinaRealtimeQuoteService, QuoteHttpClient, QuoteService (3 realtime providers co-located)
- `recap/` — DailyRecapService, MarketRecapService, FundFlowService
- `ai/` — already exists, extend with NotificationDispatcher / WishPool* / OCR
- `study/` — StudyService, StudyUploadService
- `potential/` — PracticalSelectService, PracticalSelectPdfService, PotentialService, UnifiedStockResearchService
- `notification/` — NotificationDispatcher, NotificationService, PriceMonitorService

Estimated ~150 import lines change.

**ETA:** 2-3 days, mechanical.

---

## Backlog (later sprints)

- Sprint 3: JPA batching, connection pool tuning, unified HTTP client, gzip/ETag
- Sprint 4: JaCoCo + Testcontainers, frontend bundling
- Sprint 5: Prometheus/Micrometer, restart.sh split, README/CHANGELOG
