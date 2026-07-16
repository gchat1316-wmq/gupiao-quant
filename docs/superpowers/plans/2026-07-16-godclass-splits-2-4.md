# Sprint 2.4 — God-Class Splits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans.

**Goal:** Split the six largest `service/*.java` files (each > 850 lines) into focused, single-responsibility beans of < 300 lines each. After this sprint, no service class exceeds 500 lines, and the public surface of each module is clearly delineated.

**Architecture:** For each god-class:
1. Read the file end-to-end; identify natural seams (group related methods into phases/stages).
2. Extract helper classes (`XxxPhaseExecutor`, `XxxResultBuilder`) and orchestration beans. Move public entry-point methods to a thin "main" facade that delegates to the helpers.
3. Preserve the public API surface: any controller or other service calling `prosperityPickService.foo()` must continue to work. Either keep thin facade with all old methods delegating, OR refactor callers in the same commit. The plan default is "facade" for minimal risk.
4. No behavioral changes. Same logic, same DB queries, same tests (where they exist).
5. Each task is a separate sub-plan; we tackle them in order of business risk:
   - Task 1: AuthService (smallest, mostly extracted from AuthController split)
   - Task 2: InvestService (855 lines, well-understood)
   - Task 3: ProspectPickService (1191 lines, complex pipeline)
   - Task 4: StockAnalysisService (1173 lines, similar)
   - Task 5: PracticalSelectService (1142)
   - Task 6: PotentialService (1105)
   - Task 7: UnifiedStockResearchService (1102) — orchestration layer

**Tech Stack:** Same as project — Spring Boot 3.2.5, Java 17, Lombok, no new deps.

---

## ⚠️ Hard constraints

1. **No behavior changes.** Public API of each service stays byte-equivalent; facade delegates to new helpers.
2. **No test additions required** for the existing functionality (this is a refactor, not feature work). If a test breaks, that signals the refactor changed something.
3. **Each split is its own PR/commit**. Easy to revert one without losing the rest.

---

## Task 1: AuthService (sanity check / warm-up)

`AuthService.java` was used by the recently-split AuthControllers. Likely fewer public methods. Skim it, identify seams, split if > 400 lines, otherwise mark as no-op (already small from Sprint 2.3 refactor before).

**Verify:** if line count ≤ 500, mark Task 1 complete with "no change needed" commit message.

---

## Task 2: InvestService (855 lines)

Read `src/main/java/com/quant/service/InvestService.java` end-to-end.

**Likely seams** (verify by reading):
- Pool CRUD (add/remove/list stocks)
- Position tracking (entry, lots, average cost calculation)
- Valuation (10×PS calculation)
- OCR / seed-data import
- Display / query

**Proposed split** (5 helper services):
- `InvestPoolService` (CRUD on `invest_stock_pool`)
- `InvestPositionService` (entry/lots/avg_cost math, persistence)
- `InvestValuationService` (10×PS)
- `InvestOcrImportService` (image → pool entry helper) — already exists as OcrPoolImportService?
- `InvestService` (thin facade; ~150 lines; orchestrates the 4 helpers + preserves public API)

**Rules:**
- Each helper < 300 lines
- Each helper has a single responsibility expressed by class javadoc
- InvestService facade methods: copy old method bodies and rewrite to delegate. Keep method signatures identical so existing controllers work.
- Use Lombok `@RequiredArgsConstructor` to inject helpers into facade.

**Verification:**
```bash
mvn compile -q -DskipTests
wc -l src/main/java/com/quant/service/Invest*.java
# Total of all 5 files < 1500 (vs 855 original + helpers, but expect ~700 spread across files)
```

---

## Task 3: ProsperityPickService (1191 lines)

Read `src/main/java/com/quant/service/ProsperityPickService.java`.

**Likely seams:**
- Stage 1: Input collection (sectors + leader candidates from Wind / TDX)
- Stage 2: Financial hard filter (apply revenue_yoy / deducted_np_yoy / gross_margin thresholds)
- Stage 3: Mainline reason analysis
- Stage 4: 9-dimension scoring
- Stage 5: Verdict generation (LOW_VALUATION / FAIR / OVERVALUED)
- Stage 6: Report HTML generation
- Pipeline orchestration (existing `prosperitystrong/ProsperityStrongPipelineService` already partial)

**Proposed split:**
- `prosperitystrong/` already exists as a sub-package — put new helpers there
- `SectorCandidateCollector` (~200 lines, Stage 1)
- `FinanceFilterExecutor` (~150 lines, Stage 2)
- `MainlineReasonAnalyzer` (~150 lines, Stage 3)
- `NineDimensionScorer` (~200 lines, Stage 4)
- `ProsperityPickService` becomes thin facade (~200 lines)
- Reuse existing `ProsperityStrongPipelineService`

**Verification:**
```bash
mvn compile -q -DskipTests
find src/main/java/com/quant/service/prosperitystrong -name '*.java' | xargs wc -l | sort -rn | head -10
# All < 400 lines
```

---

## Task 4: StockAnalysisService (1173 lines)

Read `src/main/java/com/quant/service/StockAnalysisService.java`.

**Likely seams:**
- Multi-source data collection: BaoStock, TDX, Wind, EASTMONEY
- Each source has its own collector
- Verdict + moat score computation
- PDF rendering delegation (already a separate service `StockAnalysisPdfService`?)
- HTML report generation

**Proposed split:** Mirror Prosperity approach with one collector per source + thin facade.

---

## Task 5: PracticalSelectService (1142 lines)

Read `src/main/java/com/quant/service/PracticalSelectService.java`.

Similar shape. Split per phase if natural seams exist.

---

## Task 6: PotentialService (1105 lines)

Read `src/main/java/com/quant/service/PotentialService.java`.

---

## Task 7: UnifiedStockResearchService (1102 lines)

Read `src/main/java/com/quant/service/UnifiedStockResearchService.java`.

This is the largest, with possibly the most complex orchestration. Estimated 2-3 days to split. Should be the LAST task in Sprint 2.4.

---

## Per-task commit template

```bash
git add src/main/java/com/quant/service/<file>.java
git commit -m "refactor(<module>): split <GodClass> into <N> focused services

- <Service1>: <responsibility>
- <Service2>: <responsibility>
- <ServiceN>: <responsibility>
- <GodClass>: thin facade, <N> public methods delegating

No behavior changes. Same public API. mvn compile succeeds."
```

---

## Self-review checklist (per task)

- [ ] All new files < 300 lines
- [ ] Public API of the original god-class preserved (method signatures + behavior)
- [ ] `mvn compile` succeeds
- [ ] `mvn spotless:check` succeeds
- [ ] No DB schema changes
- [ ] No new dependencies

---

## Out of scope

- Adding unit tests for the new helpers (separate plan in Sprint 4)
- Renaming / re-packaging the new services (handled in Sprint 2.5 service/ package org)
