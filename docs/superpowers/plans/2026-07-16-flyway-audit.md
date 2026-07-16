# Flyway Migration Audit — captured 2026-07-16

Mapping of every `SchemaInitializer.ensureXxx()` method to its V{n} migration target, plus every `sql/*.sql` file and its position in the migration chain.

## SchemaInitializer inventory (36 ensure methods)

`src/main/java/com/quant/config/SchemaInitializer.java` line 30-67 enumerates 36 `ensureXxx()` calls in execution order. Each is a `try { CREATE/ALTER } catch { log.debug }` pattern.

| # | Method | Line | Targets module | Migration form |
|---|---|---|---|---|
| 1 | ensureAuthUserTable | 72 | auth | V1__auth_user_table.sql |
| 2 | ensureSmsCodeTable | 110 | auth | V2__sms_code_table.sql |
| 3 | ensureEmailCodeTable | 129 | auth | V2__email_code_table.sql |
| 4 | ensureLoginCodeTable | 148 | auth | V2__login_code_table.sql |
| 5 | ensureAuditLogTable | 167 | auth | V3__audit_log_table.sql |
| 6 | ensureUserNotificationLogTable | 186 | notification | V4__user_notification_log_table.sql |
| 7 | bootstrapFirstAdmin | 213 | auth | (stays in Java post-Flyway) |
| 8 | ensureXieboInvestTables | 404 | invest (xiebo) | V5__xiebo_invest_tables.sql |
| 9 | ensureXieboRecentTables | 442 | invest (xiebo recent) | V6__xiebo_recent_tables.sql |
| 10 | ensureInvestAlertTable | 344 | invest | V7__invest_alert_table.sql |
| 11 | ensureInvestBigYangSignalTable | 371 | invest (big-yang) | V8__invest_big_yang_signal_table.sql |
| 12 | ensureStockAnalysisTable | 704 | stock-analysis | V9__stock_analysis_table.sql |
| 13 | ensurePdfPathColumn | 641 | stock-analysis | V9__stock_analysis_pdf_path_column.sql (or fold into V9) |
| 14 | ensureStockAnalysisUnifiedColumns | 658 | stock-analysis | V10__stock_analysis_unified_columns.sql |
| 15 | ensureProsperityPickNewColumns | 678 | prosperity | V11__prosperity_pick_new_columns.sql |
| 16 | ensureInvestStockPoolSnapshotColumns | 532 | invest | V12__invest_stock_pool_snapshot_columns.sql |
| 17 | ensureInvestStockPoolEnum | 565 | invest | V12__invest_stock_pool_enum.sql (fold into V12) |
| 18 | ensureProsperityHotSectorAStockColumns | 510 | prosperity | V13__prosperity_hot_sector_astock_columns.sql |
| 19 | ensureProsperityLeaderMainlineReason | 595 | prosperity | V14__prosperity_leader_mainline_reason.sql |
| 20 | ensureProsperityLeaderFinanceColumns | 617 | prosperity | V15__prosperity_leader_finance_columns.sql |
| 21 | ensurePipelineRunTable | 318 | prosperity | V16__pipeline_run_table.sql |
| 22 | ensurePickDailyMemoColumn | 290 | prosperity | V17__pick_daily_memo_column.sql |
| 23 | ensurePickDailyRevenueYoyMin3q | 304 | prosperity | V17 (fold) |
| 24 | ensureProsperityStockPoolTable | 255 | prosperity | V18__prosperity_stock_pool_table.sql |
| 25 | ensureProsperityStockPoolOwnerId | 745 | prosperity | V18 (fold) |
| 26 | ensurePageViewStatTable | 770 | stats | V19__page_view_stat_table.sql |
| 27 | ensureUserDailyStatTable | 793 | stats | V20__user_daily_stat_table.sql |
| 28 | ensureInvestPoolMetaTable | 821 | invest | V21__invest_pool_meta_table.sql |
| 29 | ensureInvestPoolMetaSeed | 849 | invest | V22__invest_pool_meta_seed.sql (data) |
| 30 | ensureWeeklyOpportunitySlotTable | 870 | invest | V23__weekly_opportunity_slot_table.sql |
| 31 | ensureXieboWeeklyOpportunitySlotTable | 901 | invest | V23 (fold) |
| 32 | ensureInvestPositionCommon | 929 | position | V24__invest_position_common.sql |
| 33 | ensureInvestQuoteTable | 1121 | invest | V25__invest_quote_table.sql |
| 34 | ensureJournalTables | 1145 | journal | V26__journal_tables.sql |
| 35 | ensureMonitorFusionColumns | 1190 | monitor | V27__monitor_fusion_columns.sql |
| 36 | ensureWishPoolTable | 1223 | wish-pool | V28__wish_pool_table.sql |

## sql/ directory inventory (25 files)

| File | Topic | Migration position |
|---|---|---|
| `wucai_trade.sql` | Original schema (pre-SchemaInitializer) | V0__legacy_baseline.sql (snapshot only, not run if tables exist) |
| `auth_init.sql` | auth init | (overlaps V1-V4 — fold into existing) |
| `auth_user_alter_serverchan_key.sql` | Single column ADD | V4__auth_user_serverchan_key_column.sql |
| `prosperity_strong_init.sql` | prosperity base | V11__prosperity_init.sql |
| `prosperity_strong_alter_v2.sql` | ALTER | V11 (fold) |
| `prosperity_strong_alter_v3.sql` | ALTER | V13 (or V29) |
| `prosperity_strong_alter_v4.sql` | ALTER | V13 |
| `prosperity_strong_alter_v5.sql` | ALTER | V13 |
| `stock_analysis_init.sql` | stock-analysis init | V9 (fold) |
| `stock_analysis_unified_alter.sql` | ALTER | V10 (fold) |
| `invest_quote_init.sql` | invest quote | V25 (fold) |
| `invest_weekly_opportunity_slot.sql` | weekly opportunity slot | V23 (fold) |
| `journal_init.sql` | journal | V26 (fold) |
| `monitor_fusion_v1_init.sql` | monitor fusion init | V27 (fold) |
| `wish_pool_init.sql` | wish pool | V28 (fold) |
| `xiebo_recent_init.sql` | xiebo recent | V6 (fold) |
| `lynch_invest_init.sql` | lynch invest | (NOT in SchemaInitializer; needs new V29) |
| `practical_select_init.sql` | practical-select | (NOT in SchemaInitializer; needs new V30) |
| `tech_ai_alert_thresholds_alter.sql` | tech-ai ALTER | (NOT in SchemaInitializer; needs new V31) |
| `tech_ai_position_alter.sql` | tech-ai ALTER | V31 (fold) |
| `tech_ai_valuation_import_20260630.sql` | tech-ai DATA IMPORT | V32__tech_ai_valuation_seed.sql (after migration) |
| `innovative_drug_import_2026_06_30.sql` | data IMPORT | (curiosity; check if referenced by code) |
| `innovative_drug_move_2026_06_30.sql` | data MOVE | (verify reference) |
| `market_recap_multi_day_evaluation.sql` | market recap ALTER | (likely V33 if not in SchemaInitializer) |
| `pool_unification_20260702.sql` | pool unification | (likely V34) |

## Conflicts and decisions

1. **`bootstrapFirstAdmin` stays in Java** — this creates a runtime admin user, not DDL. Will live in a post-Flyway `@Component` called `FirstAdminBootstrap` running at `@Order(2)`.

2. **sql/wucai_trade.sql is the legacy full schema** — predates Spring Boot. It is the **baseline** for prod. Flyway needs `baseline-on-migrate=true` and `baseline-version=0` so existing prod DBs don't try to run init scripts.

3. **Overlapping migrations** — SchemaInitializer's ensure methods overlap with sql/*.sql (same tables/columns). When converting, **prefer the SchemaInitializer version** (it's more recent and matches Spring Boot era data). sql/*.sql becomes a fallback archive.

4. **Pure-data files** — `*_import_*.sql` files are seed data. These can be Flyway V__ files but with `-- Flyway:data-only` comments. The data imports are 2026-06-30 dated — probably history.

5. **`tech_ai_*` and `lynch_*`, `practical_*` modules** — these are NOT covered by SchemaInitializer. Either SchemaInitializer was incomplete (these were added after, used sql/ exclusively) or these are intentional gaps. Audit decision: include them as V29-V32.

## Implementation plan (high level)

1. Create `src/main/resources/db/migration/` directory.
2. Translate SchemaInitializer.ensureXxx() into V1-V28 SQL files (alphabetical by module then numeric).
3. Add V29-V32 for the `sql/`-only modules.
4. `V999__disable_legacy.sql` with `SELECT 1` as a baseline marker.
5. Replace SchemaInitializer body with `@PostConstruct`/`CommandLineRunner` only for `FirstAdminBootstrap`.
6. Drop SchemaInitializer entirely after verification.
7. Cutover runbook for prod: `baseline-on-migrate=true`, schema snapshot of current state.

## Risks and rollback

- **Risk**: prod DB schema is currently bootstrapped by SchemaInitializer running on every boot. If Flyway baseline doesn't match reality, deployments will break.
  - **Mitigation**: Generate a one-time baseline script reflecting prod's actual schema (via `mysqldump --no-data`), save as V0__baseline.sql, run with `baseline-version=0`.
- **Risk**: Devs running locally already have partial SchemaInitializer state. First boot with Flyway will see missing migrations.
  - **Mitigation**: Use `spring.flyway.baseline-on-migrate=true` for dev; for prod, manually run `flyway baseline` against the current prod DB once.
- **Rollback**: Flyway `flyway.undo-migration` requires paid plugin; we have community edition. Real rollback = `git revert` the migration file + manual SQL fix.

## Out of scope (for this Sprint 2.1)

- Data migrations (involving user data, trade data) — separate plan if needed.
- Multi-datasource setup — current codebase is single-DB; keep it.
- Testcontainers integration testing — separate plan in Sprint 4.
