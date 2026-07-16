# Sprint 2.1 — Flyway Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking. **Per user preference, inline execution is preferred over per-task subagent dispatch.**

**Goal:** Replace `SchemaInitializer.java` (1250 lines, 36 `ensureXxx()` methods) + `sql/*.sql` (25 ad-hoc files, no versioning) with Flyway versioned migrations under `src/main/resources/db/migration/V{n}__{module}.sql`. Establish a versioned schema contract for all future changes.

**Architecture:** Spring Boot 3.2.5 already auto-wires Flyway if `flyway-core` is on the classpath. We add `flyway-mysql` (community build of MySQL support), point `spring.flyway.locations` at `classpath:db/migration`, and enable `baseline-on-migrate=true` for the prod cutover. Each migration is a self-contained `.sql` file matching Flyway's `V{n}__{description}.sql` naming convention. Run once on each environment; Flyway tracks state in `flyway_schema_history`. Migrations execute in V-number order.

**Tech Stack:** Flyway 9.22 (compatible with Spring Boot 3.2.5 — verify in pom), MySQL 8 (existing driver), no new Java APIs (Flyway runs as `SpringBootFlyway` auto-config before our beans).

**Reference audit:** `docs/superpowers/plans/2026-07-16-flyway-audit.md` — full mapping of methods → migrations.

---

## ⚠️ Hard constraints

1. **Prod schema state is unknown.** Use `baseline-on-migrate=true` and set `baseline-version=0`. Generate `V0__baseline.sql` only as a fallback for fresh DBs; for the prod DB with SchemaInitializer-built tables, do NOT run V0 (Flyway sees tables exist and skips).
2. **Migration DDL must be byte-identical to SchemaInitializer's String blocks.** Don't refactor naming, constraint order, or column types while migrating. Refactors are separate plans.
3. **`bootstrapFirstAdmin` is data, not DDL** — stays in Java post-Flyway. Move to a new `FirstAdminBootstrap` `@Component @Order(2)`.
4. **No silent data loss.** Every step preserves: schema name, column names, column types, indexes, foreign keys, character set, collation.

---

## Task 1: Add Flyway dependencies + config

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add Flyway deps to `pom.xml`**

Find the `<dependencies>` block, add inside:

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

These are managed by `spring-boot-dependencies` BOM — version comes from the parent POM automatically. Verify by running `mvn dependency:tree | grep flyway`.

- [ ] **Step 2: Add Flyway config to `application.yml`**

After the `spring.jpa` block (find it, add after the closing brace), insert:

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 0
    baseline-description: "SchemaInitializer-era baseline (treated as already migrated)"
    table: flyway_schema_history
    placeholder-replacement: false
  jpa:
    ...
```

(Keep the existing `jpa` block — just add `flyway` as a sibling top-level under `spring`.)

- [ ] **Step 3: Verify Spring Boot auto-config picks it up**

```bash
mvn dependency:tree | grep -i flyway
```

Expected: both `flyway-core` and `flyway-mysql` listed.

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/main/resources/application.yml
git commit -m "build(deps): add flyway-core + flyway-mysql for versioned migrations"
```

---

## Task 2: Create migration directory + translate V1-V10 (auth + early modules)

**Files:**
- Create: `src/main/resources/db/migration/` (directory)
- Create: `src/main/resources/db/migration/V1__auth_user_table.sql`
- Create: `src/main/resources/db/migration/V2__sms_code_table.sql`
- Create: `src/main/resources/db/migration/V3__email_code_table.sql`
- Create: `src/main/resources/db/migration/V4__login_code_table.sql`
- Create: `src/main/resources/db/migration/V5__audit_log_table.sql`
- Create: `src/main/resources/db/migration/V6__user_notification_log_table.sql`
- Create: `src/main/resources/db/migration/V7__auth_user_serverchan_key.sql`
- Create: `src/main/resources/db/migration/V8__xiebo_invest_tables.sql`
- Create: `src/main/resources/db/migration/V9__xiebo_recent_tables.sql`
- Create: `src/main/resources/db/migration/V10__invest_alert_table.sql`

- [ ] **Step 1: For each SchemaInitializer ensure method, copy the inner String block verbatim into a corresponding V{n}__*.sql file**

The mapping is in the audit doc (Task 13). For example:

`SchemaInitializer.ensureAuthUserTable()` (line 72-108) blocks → `V1__auth_user_table.sql` with the same `CREATE TABLE auth_user (...) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci` content.

Rules:
- Strip leading/trailing whitespace from the Java String block but preserve `;` and newlines.
- Strip the surrounding `jdbc.execute("""...""")` — keep only the SQL itself.
- ENGINE, CHARSET, COLLATE clauses must match exactly.
- Indexes, FK constraints must be in the same CREATE TABLE statement (Flyway doesn't tolerate partial migrations).

For ALTER-only ensure methods (e.g., `ensureInvestStockPoolSnapshotColumns` which only ADDs columns), create a separate V if the table was created in an earlier V. E.g.:
- If `ensureProsperityHotSectorAStockColumns` ADDs columns to a table that was created in `V{earlier}__prosperity_init.sql`, fold the ALTERs into V{earlier} as additional `ALTER TABLE ... ADD COLUMN IF NOT EXISTS ...` statements (MySQL 8 doesn't have `IF NOT EXISTS` for ADD COLUMN universally — use stored procedure approach if needed, OR keep it as a separate V11).

For V7 (single ALTER for auth_user.serverchan_key column), copy `auth_user_alter_serverchan_key.sql` content from `sql/auth_user_alter_serverchan_key.sql`.

- [ ] **Step 2: Spot-check first 3 V files for byte-fidelity**

```bash
# Compare auth_user table columns across java source vs new SQL file
grep -oE "CREATE TABLE auth_user \([^)]+\)" src/main/java/com/quant/config/SchemaInitializer.java | head -1
grep -oE "CREATE TABLE auth_user \([^)]+\)" src/main/resources/db/migration/V1__auth_user_table.sql | head -1
```

Expected: identical.

- [ ] **Step 3: Commit (atomic per file, or one commit per 2-3 files for tighter review)**

```bash
git add src/main/resources/db/migration/V1__auth_user_table.sql src/main/resources/db/migration/V2__sms_code_table.sql
# ... etc
git commit -m "feat(migration): V1-V10 — auth + xiebo modules SchemaInitializer → Flyway"
```

Strategy: commit in 3 batches of ~3-4 files each to keep history browseable. This is a deliberate trade-off — fewer commits = noisier history but easier revert; more commits = cleaner but slower.

---

## Task 3: V11-V20 (prosperity + analysis + stats)

**Files:** 10 V__ files following the audit mapping.

- [ ] **Step 1: Translate V11-V20**

Use the same byte-fidelity rule. Notable: V15-V16 (prosperity pipeline_run, pick columns), V17-V18 (prosperity stock pool), V19-V20 (stats).

- [ ] **Step 2: Handle `prosperity_strong_*` ALTERs**

`sql/prosperity_strong_alter_v2.sql` through `v5.sql` are ALTERs. Fold them into V11 (prosperity_init) since the same module:

```bash
cat sql/prosperity_strong_init.sql > V11__prosperity_init.sql
cat sql/prosperity_strong_alter_v2.sql sql/prosperity_strong_alter_v3.sql sql/prosperity_strong_alter_v4.sql sql/prosperity_strong_alter_v5.sql >> V11__prosperity_init.sql
# Optional: review the combined file with code-reviewer agent or self-review
```

Same for `stock_analysis_init.sql` + `stock_analysis_unified_alter.sql` → fold into V{stock_analysis} file.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V11__*.sql ... V20__*.sql
git commit -m "feat(migration): V11-V20 — prosperity + stock-analysis + stats modules"
```

---

## Task 4: V21-V28 (invest pool + position + journal + monitor + wish-pool)

**Files:** 8 V__ files.

- [ ] **Step 1: Translate V21-V28**

Notable: V22 (`invest_pool_meta_seed`) is data, not DDL — it's only run if the table is empty. Wrap with a `SELECT IF(...)` guard or document as "must-run once on init".

V25 (`invest_quote`) — copy from `sql/invest_quote_init.sql`.

V26 (`journal`) — copy from `sql/journal_init.sql`.

V27 (`monitor_fusion`) — copy from `sql/monitor_fusion_v1_init.sql`.

V28 (`wish_pool`) — copy from `sql/wish_pool_init.sql`.

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/db/migration/V21__*.sql ... V28__*.sql
git commit -m "feat(migration): V21-V28 — invest pool + position + journal + monitor + wish-pool"
```

---

## Task 5: V29+ for sql-only modules

**Files:** V29-V32 (or as many as needed).

- [ ] **Step 1: Cover the sql/ files not yet in V__**

Modules missing from SchemaInitializer:
- `lynch_invest_init.sql` → V29
- `practical_select_init.sql` → V30
- `tech_ai_alert_thresholds_alter.sql` + `tech_ai_position_alter.sql` → V31
- `tech_ai_valuation_import_20260630.sql` → V32 (data)

Verify by `git grep -l 'tech_ai\|lynch_invest\|practical_select' src/main/java/com/quant/entity/ src/main/java/com/quant/repository/`. If those JPA entities exist, the migrations are needed; if not, the sql/ files are dead code (move to `_archive`).

- [ ] **Step 2: For data imports (`tech_ai_valuation_import_*` and `innovative_drug_*`), decide**

If entities reference these tables: include as V__ with data.
If unused: move `*.sql` to `sql/_archive/` and don't migrate.

Use `git grep` on the entity name to see if it's referenced.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V29_*.sql ...
git commit -m "feat(migration): V29+ — sql/-only modules (lynch, practical-select, tech-ai)"
```

---

## Task 6: Replace SchemaInitializer body with FirstAdminBootstrap

**Files:**
- Modify: `src/main/java/com/quant/config/SchemaInitializer.java`
- Create: `src/main/java/com/quant/config/FirstAdminBootstrap.java`

- [ ] **Step 1: Move `bootstrapFirstAdmin`, `generateSecurePassword` to `FirstAdminBootstrap.java`**

```java
package com.quant.config;

import com.quant.entity.User;
import com.quant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 首次启动自举 ADMIN 用户 — 必须在 Flyway 完成所有 V__ migrations 之后跑。
 * 任何既有用户的 boot 都不会触发（仅当 userRepository.count() == 0 时）。
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class FirstAdminBootstrap implements ApplicationRunner {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(org.springframework.boot.ApplicationArguments args) {
        if (userRepository.count() > 0) {
            boolean hasEnabledAdmin = userRepository.findAll().stream()
                    .anyMatch(u -> u.getRole() == User.Role.ADMIN && !Boolean.TRUE.equals(u.getDisabled()));
            if (!hasEnabledAdmin) {
                log.warn("【安全恢复】未发现可用管理员，将重置现有 ADMIN 账号...");
                userRepository.findAll().stream()
                        .filter(u -> u.getRole() == User.Role.ADMIN)
                        .forEach(u -> { u.setDisabled(false); userRepository.save(u); });
                log.warn("【安全恢复】ADMIN 账号已恢复可用，请立即登录并检查安全设置。");
            }
            return;
        }

        String rawPassword = generateSecurePassword(16);
        User admin = new User();
        admin.setUsername("admin");
        admin.setRole(User.Role.ADMIN);
        admin.setPasswordHash(passwordEncoder.encode(rawPassword));
        userRepository.save(admin);

        log.warn("═══════════════════════════════════════════════════════");
        log.warn("【首次启动】系统已自动创建管理员账号：");
        log.warn("  用户名：admin");
        log.warn("  密码：{}", rawPassword);
        log.warn("请立即登录并修改密码！");
        log.warn("═══════════════════════════════════════════════════════");
    }

    private String generateSecurePassword(int length) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).substring(0, length);
    }
}
```

- [ ] **Step 2: Strip SchemaInitializer.java down to a no-op or delete it**

Two options:
- **Option A (preferred):** delete the file entirely. `FirstAdminBootstrap` takes over the only non-DDL responsibility.
- **Option B (rollback safety):** keep SchemaInitializer as a thin `@Component` with an empty `run()` method.

Go with **A** for cleanliness. If something breaks, `git revert` brings it back.

- [ ] **Step 3: Compile and verify**

```bash
mvn compile -q -DskipTests
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add -A src/main/java/com/quant/config/
git commit -m "refactor: extract FirstAdminBootstrap from SchemaInitializer; DDL now lives in Flyway"
```

---

## Task 7: End-to-end verification

- [ ] **Step 1: Compile + package**

```bash
mvn clean package -DskipTests
```

Expected: BUILD SUCCESS, jar at `target/gupiao-quant-1.0.0.jar`.

- [ ] **Step 2: Run Flyway against a fresh dev DB**

```bash
# Ensure no DB pollution. Use a local MySQL or test against sqlite fallback if available.
mvn spring-boot:run -Dspring-boot.run.profiles=local 2>&1 | head -30
# Look for: "Flyway Community Edition X.Y.Z by ..."
# Look for: "Successfully applied X migrations to schema ..."
```

Expected: log shows all V__ ran successfully.

- [ ] **Step 3: Verify flyway_schema_history table populated**

```bash
mysql -u<user> -p<pass> wucai_trade -e "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

Expected: rows for each V__ that ran, all `success=1`.

- [ ] **Step 4: Verify table count matches pre-Flyway**

```bash
mysql -u<user> -p<pass> wucai_trade -e "SHOW TABLES;" | wc -l
```

Expected: roughly the same as `git show HEAD:src/main/java/com/quant/config/SchemaInitializer.java | grep -c 'CREATE TABLE'` (a rough proxy).

- [ ] **Step 5: Idempotency check**

Stop and restart the app. Confirm Flyway log shows "Schema is up to date. No migration necessary." (no re-run).

```bash
# Stop current app (Ctrl-C), restart
mvn spring-boot:run -Dspring-boot.run.profiles=local 2>&1 | grep -i flyway | head -3
```

Expected: "up to date" message.

- [ ] **Step 6: Run mvn test (full suite, not skipping)**

```bash
mvn test
```

Expected: all 70+ tests pass within ~5 minutes. If a test is now broken because of Flyway ordering, fix the migration order in a follow-up commit.

---

## Task 8: Cutover runbook

**Files:**
- Create: `docs/superpowers/plans/2026-07-16-flyway-cutover-runbook.md`

- [ ] **Step 1: Document the cutover steps for prod**

```markdown
# Flyway Cutover Runbook — 2026-07-16

## Current state

Prod DB was bootstrapped by `SchemaInitializer.java` over many months. Schema is highly evolved; tables and columns exist that pre-date the SchemaInitializer era (use `wucai_trade.sql` as the conceptual baseline).

## Cutover steps

1. **Pre-cutover snapshot**
   ```bash
   mysqldump -u<user> -p<pass> --no-data --routines wucai_trade > pre-flyway-schema-snapshot-$(date +%Y%m%d).sql
   gzip pre-flyway-schema-snapshot-*.sql
   cp pre-flyway-schema-snapshot-*.sql.gz /tmp/backups/
   ```

2. **Verify all V{n}__*.sql migrations are present and parse**
   ```bash
   ls src/main/resources/db/migration/V*.sql | wc -l
   # Expected: ~28-32 depending on what got cut
   ```

3. **Confirm `spring.flyway.baseline-on-migrate=true` in application.yml.** This makes Flyway recognize the existing schema as "already at baseline 0" and only run NEW migrations.

4. **First prod restart after deploy** — `restart.sh` will:
   - Boot Spring Boot
   - Flyway discovers DB exists, tables exist, sees baseline at 0
   - Runs only V__new migrations (any added since the deploy)
   - Logs "Schema is up to date" once caught up

5. **Rollback if something breaks**: `git revert <migration-commit>; mvn package; ./restart.sh`. The new V__ won't be re-applied because Flyway records it. Manual SQL fix-up may be needed if the migration was destructive.

## Operational notes

- **Adding a new column**: write a V{n+1}__add_xxx.sql with `ALTER TABLE x ADD COLUMN ...` and commit. Next deploy runs it.
- **Renaming a column**: Flyway treats RENAME as a delete + add. Better: V{n+1}__add_new_column.sql + backfill + code-update reads. Don't do RENAME in prod without a separate plan.
- **Destructive migrations**: ADD new column, dual-write in code, backfill, drop old column. Multi-release cycle.

## What this fixes

- ❌ Before: 1250-line `SchemaInitializer` running `ALTER TABLE ADD COLUMN` on every boot
- ✅ After: V__ files run once, Flyway tracks state, `restart.sh` becomes idempotent on schema.
- ✅ New schema changes are a git PR with a single SQL file, not a Java edit to 1250-line boot bean.
```

- [ ] **Step 2: Commit**

```bash
git add -f docs/superpowers/plans/2026-07-16-flyway-cutover-runbook.md
git commit -m "docs(migration): flyway cutover runbook"
```

---

## Self-review

- [ ] `src/main/resources/db/migration/` exists with V1-V{n} files
- [ ] `SchemaInitializer.java` is deleted (or empty)
- [ ] `FirstAdminBootstrap` exists and runs at `@Order(2)` (Flyway is `@Order(0)`)
- [ ] `mvn test` passes
- [ ] No entity `@Table(name=...)` references a table that DOES NOT have a corresponding V__ migration. Run: `grep -rE '@Table\(name' src/main/java/com/quant/entity/ | awk '{print $2}' | tr -d '()name=' | sort -u` and compare to `ls src/main/resources/db/migration/`.
- [ ] Each V__ file is byte-fidelity equivalent to SchemaInitializer's String block (no refactors snuck in)

## Out of scope (later sprints)

- Testcontainers integration testing — Sprint 4
- Multi-datasource — separate plan if needed
- Undo migrations (paid Flyway feature) — manual revert for now
- Data migration tooling for prod data — separate plan if needed
