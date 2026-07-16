# Flyway Cutover Runbook — 2026-07-16

## 当前状态

生产 DB 一直靠 `SchemaInitializer.java`（1250 行，`CommandLineRunner @Order(1)`）在每次启动时跑 ALTER/CREATE 来保证 schema 同步。这种"懒建"模式有 3 个问题：
1. 没有版本控制，谁也不知道线上到底有哪些列
2. 任何异常都吞掉，schema 漂移没人发现
3. 改 schema = 改 Java 代码 + 重启，PR 流程至少 30 分钟

Sprint 2.1 切到 Flyway（V1-V20，20 个 migration），生产切换一次性收敛。

## 切流前

1. **schema 快照**
   ```bash
   mysqldump -u<user> -p<pass> --no-data --routines --skip-comments wucai_trade > pre-flyway-schema-snapshot-$(date +%Y%m%d).sql
   gzip pre-flyway-schema-snapshot-*.sql
   cp pre-flyway-schema-snapshot-*.sql.gz /backups/
   ```

2. **确认所有 V{n}__*.sql 已提交**
   ```bash
   ls src/main/resources/db/migration/V*.sql | wc -l
   # 期望: 20
   ```

3. **检查 `spring.flyway` 配置**
   ```bash
   grep -A 8 'spring:' src/main/resources/application.yml | grep -A 8 flyway
   ```
   期望包括：
   - `enabled: true`
   - `locations: classpath:db/migration`
   - `baseline-on-migrate: true`
   - `baseline-version: 0`

## 切流步骤（生产）

1. 部署新 jar（已经把 Sprint 2.1 commits 合入 `main` 的版本）。启动前先确保：`SchemaInitializer.java` 已经被删除（否则会和 Flyway 双重执行）。

2. 首次启动时 Flyway 会：
   - 检测 `flyway_schema_history` 表不存在
   - 因 `baseline-on-migrate=true` + `baseline-version=0`，把当前状态视作「已经在 baseline 0」
   - 仅执行 schema_history 表的 setup（CREATE TABLE flyway_schema_history）
   - 因 baseline-version=0 且 production schema 比 V1 更新，**所有 V1-V20 默认视作已应用**，不会重跑

3. 验证：
   ```bash
   tail -200 app.log | grep -i flyway | head -30
   # 应看到：Flyway Community Edition X.Y.Z ...
   # 应看到：Creating Schema History table: ...
   # 应看到：Schema baseline at version 0 (SchemaInitializer-era ...)
   ```

4. 列出当前 DB 中所有表，与 V*.sql 创建的表对照：
   ```bash
   mysql -u<user> -p<pass> wucai_trade -e "SHOW TABLES;" > current-tables.txt
   grep -oE '^CREATE TABLE `[a-z_]+`' src/main/resources/db/migration/V*.sql | sort -u > v-tables.txt
   diff <(sed 's/`//g; s/CREATE TABLE//' v-tables.txt) <(grep -oE '[a-z_]+' current-tables.txt | tail -n +1) | head -30
   ```

5. **重启**（再次确认 Flyway 完全幂等）：
   ```bash
   ./restart.sh
   tail -50 app.log | grep -i flyway
   # 应看到："Schema is up to date. No migration necessary."
   ```

6. **首次部署后**第一次添加新字段时（用于验证 Flyway 真实生效）：
   - 编辑任意 V*.sql 后面追加一个新文件 V21__add_smoke_test_column.sql
   - 内容：`ALTER TABLE flyway_schema_history ADD COLUMN smoke_test TINYINT(1) DEFAULT 0;`（Flyway 自己的表，用来证明 Flyway 在跑）
   - 提交 + 重启
   - 看到日志里出现 "Successfully applied 1 migration to schema..."

## Rollback

Flyway Community Edition 没有 undo migration。回滚语义：

| 状况 | 处理 |
|---|---|
| 新加的 V21 列没数据 → 直接 revert commit + 重启 | Flyway 已记录的 V21 不会被重跑，但下次启动会因为 checksum 不一致抛 `MigrationVersion mismatch` → 需要 `flyway repair` 或手动 `DELETE FROM flyway_schema_history WHERE version='21'` |
| 删错列 / 改错列导致数据丢失 | git revert + 重启 + 手动 SQL 修复 |
| 整体翻车 | git revert 到 Sprint 2.1 之前某次 commit，重启 — SchemaInitializer 旧路径会重新接管（如果你没删 SchemaInitializer.java）。Sprint 2.1 切流后建议删除 SchemaInitializer.java，不能简单回退到它。需要从 mysqldump 恢复。 |

## Ops 注意事项

- **环境隔离**：dev DB 是干净状态，Flyway 会从头跑 V1-V20。生产用 baseline-on-migrate 一键跳过。staging 同理。
- **多人协作**：两人都改 V*.sql 会冲突。Flyway 通过 version 号唯一标识 migration，**不要复用 version**，新加文件用更大的 V 号。
- **Migration 内容备份**：每次新加 V 都要 committa；要找历史：`git log --all --oneline -- src/main/resources/db/migration/V{n}__*.sql`。
- **DB SSL**：DB_USE_SSL 仍由 Task 7 的 `DataSourceUrlCustomizer` 处理；Flyway 连接也会跟着走 SSL。

## 该 Sprint 修了什么

- ❌ 之前：`SchemaInitializer.java`（1250 行）每次重启跑 36 个 try-catch CREATE/ALTER
- ✅ 之后：20 个 V*.sql 文件版本化，Flyway 跑一次即收敛；新加字段只需要 commit 一个新 V 文件
- ✅ `FirstAdminBootstrap.java` 仅做首次 ADMIN 自举（Java，更适合）
- ✅ 杀掉所有 sql/*.sql 旧脚本（移到 `sql/_archive/`）
