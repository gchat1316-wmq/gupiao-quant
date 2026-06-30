# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> For AI-assistant style instructions (project overview, table-prefix conventions, env-var coverage rules), see `AGENTS.md`. This file focuses on the build/test/architecture facts you need to be productive quickly.

## What this project is

A Spring Boot 3 multi-module investment tools monolith, deployed at `https://aidaily.dpdns.org/gp/` (context-path `/gp`). Originally a single stock-financial-comparison feature; it now hosts eight subsystems sharing one MySQL schema and one Spring Boot process:

| Module | Path / class hints | Purpose |
|---|---|---|
| 股票财务查询 | `controller/StockController` | Core financial-metric comparison (毛利率/营收同比/扣非) |
| 学习搭子 (Study Buddy) | `service/StudyService`, `static/study.html`, `static/course.html`, `static/quiz.html` | Upload PDF/PPT/TXT, extract knowledge graph, flashcards, quizzes |
| 投资池 (谢博/原龙江) | `controller/InvestController`, `controller/XieboInvestController`, `static/invest.html` | Stock pool, position filling, valuation, SOP, OCR import |
| 热点选股 / 强势股 | `service/prosperitystrong/*`, `controller/ProsperityStrongController`, `controller/ProsperityPickController`, `static/prosperity-strong.html`, `static/prosperity-pick.html` | Hot-sector scanning, leader detection, financial hard-filter, candidate pool |
| 个股分析 (紫苏叶/九维) | `controller/StockAnalysisController`, `service/stockanalysis/*` | Multi-dimension analysis report via external Python |
| 科技 AI 实时监控 | `controller/TechAiController`, `service/techai/*`, `static/tech-ai.html` | QMT/xtdata real-time quotes, alerts, position advice |
| 每日复盘 | `controller/MarketRecapController`, `service/MarketRecapService` | Structured daily market recap |
| 许愿池 | `controller/WishPoolController` | Receives user wishes via Feishu webhook |

Additional cross-cutting surfaces: 认证 (`security/` + `controller/AuthController` + `controller/TdxAuthController`), 统计 (`controller/StatsController`, entities `PageViewStat`/`UserDailyStat`), 大阳线战法 (`InvestBigYang*`), 实用选股 (`PracticalSelect*`), 潜力股 (`PotentialController`).

## Commands

```bash
# Local dev (port 8080, context-path /gp → http://localhost:8080/gp/)
mvn spring-boot:run

# Build jar (skip tests during deploy)
mvn clean package -DskipTests
# Output: target/gupiao-quant-1.0.0.jar

# All tests
mvn test
# Surefire arg: -Dnet.bytebuddy.experimental=true (already set in pom.xml)

# Single test class
mvn test -Dtest=StockQueryServiceTest

# Single test method
mvn test -Dtest=StockQueryServiceTest#methodName

# Single test pattern
mvn test -Dtest='Invest*AuthTest'

# Production deploy — ALWAYS use restart.sh, never `nohup java -jar` directly
./restart.sh

# Stop production
kill $(cat run.pid)

# Tail prod log
tail -f app.log

# Health check
curl http://localhost:8080/gp/api/stock-analysis/health

# Init/refresh DB (full schema)
mysql -u<user> -p < sql/wucai_trade.sql
# Module deltas live in sql/*_init.sql, sql/*_alter_*.sql
```

`restart.sh` performs env check → drift detection (jar mtime vs running PID, git uncommitted/unpushed) → `mvn clean package -DskipTests` → launch → local readiness probe (60s) → external URL smoke → diagnostic dump on failure.

## Tech stack

- Java 17, Spring Boot 3.2.5, Maven
- Spring Data JPA + Hibernate, MySQL 8 (charset utf8mb4)
- Spring Cache + Caffeine
- Spring Security + JWT (jjwt 0.12.5), BCrypt
- Lombok (heavy use: `@Data`, `@RequiredArgsConstructor`, `@Slf4j`)
- PDFBox 2.0.31, Flexmark 0.64.8, Jackson
- WebFlux present (used by some outbound integrations)
- Frontend: vanilla HTML5/CSS3/JS + Chart.js 4 (CDN) — no build step
- External: BaoStock (Python), Wind MCP, TDX connector, QMT/xtdata, MiniMax, SenseNova, Tavily, ServerChan, Feishu webhook

## Architecture

### Package layout

```
com.quant
├── GupiaoQuantApplication        # @SpringBootApplication, @EnableScheduling, registers *Properties via @EnableConfigurationProperties
├── config/                        # @ConfigurationProperties classes (AiProperties, NotificationProperties, BaostockSyncProperties, InvestBigYangProperties)
├── controller/                    # REST endpoints, one file per feature
├── dto/                           # Response/request DTOs; sub-packages mirror modules (dto/study, dto/invest, dto/techai, ...)
├── entity/                        # JPA @Entity classes (one per table)
├── repository/                    # Spring Data JpaRepository interfaces
├── security/                      # SecurityConfig, JwtAuthFilter, JwtTokenProvider, UserPrincipal
└── service/                       # Business logic
    ├── ai/                        # MiniMaxClient / SenseNovaClient / TavilyClient wrappers
    ├── industryresearch/          # 产业投研 pipeline (LLM + PDF + news radar)
    ├── prosperitystrong/          # 热点选股 algorithm
    ├── stockanalysis/             # 个股分析 Python bridge + PDF render
    ├── techai/                    # QMT real-time quotes, TechAiSchemaGuard
    ├── tdx/                       # TDX connector client
    ├── wechat/                    # WechatMpService, WechatScanSession
    └── xieboinvest/               # 投资池 (谢博) algorithm
```

Frontend: `src/main/resources/static/{*.html, css/, js/, lib/, images/}`. There is no bundler — pages are plain HTML and reference JS/CSS directly. Layout chrome lives in `header.html` loaded via `js/layout.js`.

### Auth model

Stateless JWT (`security/JwtAuthFilter` runs before `UsernamePasswordAuthenticationFilter`). Login paths: password (BCrypt) or SMS code (`SmsService`) or WeChat (`WechatMpService` — supports both 开放平台 OAuth and 公众号带参数二维码). Token secret comes from `app.jwt.secret` (`JWT_SECRET` env var). `SecurityConfig` permitAll list includes static resources, GETs on `/api/quote/**`, `/api/stock/search`, `/api/invest/pool`, `/api/invest/sop/**`, `/api/invest/big-yang/**`, `/api/analysis/**`, `/api/news/**`, all `/api/auth/**`, and `POST /api/stats/page-view`. Everything else requires `.authenticated()`.

Roles: `USER` (default) / `ADMIN` (`auth_user.role`). Admin checks use `@PreAuthorize("hasRole('ADMIN')")` (method security enabled).

### Schema initialization

No Flyway/Liquibase. `config/SchemaInitializer` is a `CommandLineRunner @Order(1)` that runs `ensureXxx()` methods on every boot — each method calls `jdbc.execute(CREATE TABLE ...)` inside a try, and on `Exception` does incremental `ALTER TABLE ADD COLUMN` for newer columns. This is why table-create statements are usually paired with additive ALTERs. **New tables/columns should add an `ensureXxx()` method here** (and also ship a `sql/*_init.sql` or `sql/*_alter_*.sql` script for fresh DBs).

### Database

The active DB is `wucai_trade` (configured in `application.yml`, NOT `gupiao_quant` as README.md says). Tables are prefixed by module:

- `trade_*` — stock basic / daily K-line / financial (legacy from before the rename)
- `auth_*` — users, login codes, audit log
- `study_*` — 学习搭子
- `invest_*` — 投资池 / position / alert / big-yang signal
- `prosperity_*` — 热点选股 (multiple `_alter_v2..v5` deltas exist)
- `stock_analysis_*` + `stock_analysis_record` — 个股分析
- `tech_ai_*` — 科技 AI real-time
- `quote_*` — real-time quote snapshots

`SchemaInitializer` auto-creates the auth/study/invest/prosperity/stock-analysis/tech-ai/page-view/user-daily tables; for the rest, ensure `sql/` scripts have been run.

### Cron jobs

`@EnableScheduling` is on. Cron expressions live in `application.yml` under module-specific keys, e.g.:

- `baostock-sync.daily-cron` (weekday 18:20 daily BaoStock sync)
- `prosperity-strong.cron` (weekday 15:30 sector scan)
- `invest-big-yang.candidate-cron` / `trigger-cron` (weekday 18:35 candidate build + every 5min intraday trigger)
- `invest-pool.refresh-cron` (Saturday 20:30 full refresh) / `backfill-cron` (daily 16:30 NULL-fill only)
- `notification.quote-monitor.cron` (every minute 9–15 on weekdays, disabled by default)

When changing a schedule, edit `application.yml`, not the Java code.

### Python integration

`scripts/` contains BaoStock sync (`baostock_basic_sync.py`, `baostock_daily_sync.py`, `baostock_latest_5m.py`), the QMT bridge (`qmt_tech_ai_bridge.py`), the BaoStock client wrapper used by 个股分析 (`baostock_client.py`), and `render_pdf.py` for analysis PDFs. Java services invoke them via `ProcessBuilder`/`Runtime.exec` — see `BaostockSyncService` and `StockAnalysisPdfService` for the calling pattern. Python command and script paths are externalised (`baostock-sync.python-command`, `stock-analysis.python-command`/`python-script`).

### Configuration & secrets

`application.yml` uses `${ENV_VAR:default}` everywhere for sensitive values (DB creds, JWT secret, MiniMax/SenseNova/Tavily keys, ServerChan key, Feishu webhook, Wind/TDX paths, WeChat app credentials). **Defaults in the file are non-production / placeholder values**; production must override via env. `.gitignore` covers `.env`, `application-local.yml`, `secrets/`, `uploads/`, `docs/`, `app.log.*`, `run.pid`, but `application.yml` itself is in version control — keep real secrets out of it.

### Caching

Spring Cache with Caffeine. `@Cacheable` is used on read-heavy query methods (e.g. `StockQueryService`). When adding a new cacheable query, define the cache name in `application.yml` under `spring.cache.caffeine.spec` if you need TTL/max-size tuning.

### Frontend

Each HTML page is standalone (no SPA framework). Common patterns:
- `header.html` is the shared nav fragment, injected by `js/layout.js`.
- `js/app.js`, `js/invest.js`, `js/study.js` etc. are per-page logic loaded directly.
- Chart.js is loaded from CDN; charts are initialised in page-specific JS and can be exported as PNG.
- Auth-aware UI elements check `localStorage.token` and call `/api/auth/me` to render user state.

When adding a page, drop the HTML in `src/main/resources/static/`, add a CSS file under `css/`, and wire navigation in `header.html`.

## Things that bite

- `restart.sh` runs `mvn clean package` without tests — **run `mvn test` yourself before deploying**.
- The `restart.sh` drift check kills the running process if the jar is newer than the running PID's start time. If you're iterating locally without restart.sh, manually `kill $(cat run.pid)` after rebuilds.
- JPA `ddl-auto: none` + `open-in-view: false`. Don't rely on lazy loading in controllers.
- Lombok is required at compile time (`<optional>true</optional>` in pom, but the annotation processor path is wired correctly). If IntelliJ shows "lombok cannot be resolved", enable annotation processing.
- Many modules cache; if a test sees stale data, check the `@Cacheable` annotation on the underlying service.
- `application.yml` contains real-looking API keys with placeholder-style defaults. Do not commit changed defaults without ensuring they remain non-production values.

## Source-of-truth priority

When `AGENTS.md`, `README.md`, `plan.md`, and the code disagree, the **code wins**. `README.md` describes only the original stock-financial feature and is significantly out of date; `AGENTS.md` is the more current AI-assistant reference but also lags in some details. For any specific behaviour, trust the running code and `application.yml` over either doc.