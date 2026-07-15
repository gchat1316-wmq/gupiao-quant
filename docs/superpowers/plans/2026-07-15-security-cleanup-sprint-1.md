# Sprint 1 — Security Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Strip every hardcoded secret / DB password / API key out of `src/main/resources/application.yml`, split the config across profile-specific files (one public, two examples), enforce those files via `.gitignore`, silence DB-in-transit exposure (`useSSL=true`), and stage the verification artifacts out of the repo root. Prod env-vars must be set BEFORE this ships or the app will fail to start — a config-validator guards that.

**Architecture:** Introduce a Spring profile-aware config split:
- `application.yml` — public defaults only (placeholder/empty strings, env-var indirection, no real secrets)
- `application-local.yml` — dev profile overrides (in `.gitignore`, never committed)
- `application-local.yml.example` — committed template showing what `application-local.yml` should contain
- `application-prod.yml.example` — committed template showing the prod shape (real values live in K8s Secret / ops key-vault, not in the repo)

Add a `StartupConfigValidator` (`ApplicationRunner @Order(0)`) that fails-fast when a sensitive property resolves to the placeholder default in non-`local` profiles — without it, a missed env var silently produces degraded auth (anyone can forge a JWT with the placeholder secret).

**Tech Stack:** Spring Boot 3.2.5 (`Environment`, `@ConfigurationProperties`, profile activation via `--spring.profiles.active`), `bcrypt` already in tree, no new deps.

---

## ⚠️ HARD CONSTRAINTS (read before starting)

1. **The currently running prod instance depends on hardcoded defaults in `application.yml`.** After Task 5 ships, prod WILL fail to start on first boot unless (a) env vars are exported in the systemd unit / K8s Secret, or (b) `application-prod.yml` is mounted with the real values. Task 8 is what guards against this — Task 5 must NOT be deployed standalone.
2. **DB SSL toggle (Task 7) requires the MySQL server to have SSL enabled AND a CA cert accessible.** If the server only supports plaintext, `useSSL=true` will refuse the connection. If unsure, ship Task 7 in a follow-up commit, gated on `DB_USE_SSL` env var defaulting to `false` (current behavior) — flip to `true` only after confirming the server has SSL.
3. **The "rotate leaked secrets" item is a manual user action — not automatable in this codebase.** Plan records it as `docs/superpowers/plans/2026-07-15-secret-rotation-checklist.md` (user checks off as they regenerate). Tasks in this plan reduce future leakage but cannot retroactively un-leak the values already in `.git` history.

---

## Task 1: Snapshot the secret inventory

**Files:**
- Create: `docs/superpowers/plans/2026-07-15-secret-inventory.md`

- [ ] **Step 1: Capture the current state for the audit trail**

Run this one-liner to list every secret-bearing line in the current `application.yml`:

```bash
grep -nE '(password|secret|key|token|webhook)[^:]*:' src/main/resources/application.yml \
  | grep -vE '^\s*#|no-|disabled|allowed|exclude-filter|expire|cooldown|code-id|mock|cache|public-id' \
  | head -40
```

Expected output should include at least:
- Line 15: `${DB_PASSWORD:wmq534@...}`
- Line 61: `${JWT_SECRET:change-this-secret-key-in-production-please}`
- Line 131: `api-key: TDX-c62ebd...`
- Line 156: `${SERVER_CHAN_SEND_KEY:SCT354970T...}`
- Line 179: webhook URL for Feishu
- Line 198: `AI_MINIMAX_KEY` default
- Line 206: `SENSENOVA_API_KEY` (double-nested)
- Line 214: `tvly-dev-6Rg1a-...`

- [ ] **Step 2: Write the inventory file**

Write `docs/superpowers/plans/2026-07-15-secret-inventory.md` with the exact env-var name, current default value, and which Sprint-1 task replaces it. Use this template:

```markdown
# Secret Inventory — captured 2026-07-15

| # | Property path | Env var | Current default (LEAKED) | Owner | Sprint-1 replacement |
|---|---|---|---|---|---|
| 1 | `spring.datasource.password` | `DB_PASSWORD` | `wmq534@...` | infra | Task 5: empty default |
| 2 | `app.jwt.secret` | `JWT_SECRET` | `change-this-secret-key-in-production-please` | auth | Task 5: empty default, validator blocks in non-local |
| 3 | `prosperity-strong.tdx.api-key` | `TDX_API_KEY` | `TDX-c62ebd...` | infra | Task 5: empty default |
| 4 | `notification.serverchan.send-key` | `SERVER_CHAN_SEND_KEY` | `SCT354970T...` | ops | Task 5: empty default |
| 5 | `notification.wish-pool.webhook-url` | `WISH_POOL_FEISHU_WEBHOOK_URL` | full URL | ops | Task 5: empty default |
| 6 | `ai.minimax.api-key` | `AI_MINIMAX_KEY` | `sk-cp-E09-...` | AI | Task 5: empty default |
| 7 | `ai.sensenova.api-key` | `SENSENOVA_API_KEY` / `AI_SENSENOVA_KEY` | `sk-tNFEPGZZ...` | AI | Task 5: empty default |
| 8 | `ai.tavily.api-key` | `TAVILY_API_KEY` | `tvly-dev-6Rg1a-...` | AI | Task 5: empty default |
| 9 | `app.sms.huaxin.username` | `HUAXIN_SMS_USER` | (empty — OK) | SMS | already clean |
| 10 | `app.wechat.*` | `WECHAT_*` | (all empty — OK) | auth | already clean |
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/plans/2026-07-15-secret-inventory.md
git commit -m "docs: capture pre-cleanup secret inventory for Sprint 1 audit"
```

---

## Task 2: Expand `.gitignore`

**Files:**
- Modify: `.gitignore` (append a new section after line 86)

- [ ] **Step 1: Append new ignore rules**

Open `.gitignore`. After the existing `# 环境与密钥（绝对不能提交）` section (ends with `secrets/` on line 86), append:

```gitignore
# ======================================
# Profile-specific application 配置（绝对不能提交）
# ======================================
application-prod.yml
application-prod.yaml
application-staging.yml
application-staging.yaml
application-dev.yml
application-dev.yaml

# ======================================
# Sprint 1 临时产物（已搬移/迁移完毕）
# ======================================
ai-compute-dashboard.html
verification_report.html
verification_screenshots/
```

- [ ] **Step 2: Verify nothing currently-tracked gets clobbered**

```bash
git check-ignore -v ai-compute-dashboard.html verification_report.html application-prod.yml
```

Expected: each path prints an `.gitignore:` line showing the rule that matched. If git replies that the path is NOT ignored, the rule isn't taking effect (typo / wrong section).

- [ ] **Step 3: Confirm `application-local.yml.example` and `application-prod.yml.example` are NOT ignored**

```bash
git check-ignore -v application-local.yml.example application-prod.yml.example
```

Expected: `git check-ignore` exits non-zero with "no matching pattern" — these example files must commit.

- [ ] **Step 4: Commit**

```bash
git add .gitignore
git commit -m "chore(gitignore): exclude profile configs and one-off verification artifacts"
```

---

## Task 3: Stage verification artifacts out of repo root

**Files:**
- Move: `ai-compute-dashboard.html` → `docs/samples/ai-compute-dashboard.html`
- Move: `verification_report.html` → `docs/samples/verification_report.html`
- Move: `verification_screenshots/` → `docs/samples/verification_screenshots/`
- Modify: `.gitignore` — remove the now-redundant rules added in Task 2

- [ ] **Step 1: Create the destination directory**

```bash
mkdir -p docs/samples
```

- [ ] **Step 2: Move with `git mv` (so history is preserved if these were ever tracked)**

```bash
git mv ai-compute-dashboard.html docs/samples/ai-compute-dashboard.html 2>/dev/null || mv ai-compute-dashboard.html docs/samples/
git mv verification_report.html docs/samples/verification_report.html 2>/dev/null || mv verification_report.html docs/samples/
[ -d verification_screenshots ] && (git mv verification_screenshots docs/samples/verification_screenshots 2>/dev/null || mv verification_screenshots docs/samples/verification_screenshots)
```

`git mv` may fail if these files were never tracked (which the `ls -la` output suggested). Fall back to plain `mv`. Don't error out on `git mv` failures.

- [ ] **Step 3: Verify cleanup**

```bash
ls ai-compute-dashboard.html verification_report.html verification_screenshots 2>&1 || true
ls docs/samples/
```

Expected: first command returns "No such file or directory"; `docs/samples/` lists the three moved items.

- [ ] **Step 4: Write a README in `docs/samples/` so future contributors don't re-introduce them at root**

Create `docs/samples/README.md`:

```markdown
# Samples

One-off verification artifacts and ad-hoc dashboards generated during development/QA.
Keep nothing here permanently — re-render to the final destination and delete.

Not deployed. `git mv` from repo root, do not commit new files at `/docs/samples/`.
```

- [ ] **Step 5: Trim `.gitignore`**

Remove the lines added in Task 2 for `ai-compute-dashboard.html`, `verification_report.html`, `verification_screenshots/` (they now live under `docs/samples/` and `docs/` is already ignored — see line 102 of the original `.gitignore`).

```bash
grep -nE '(ai-compute-dashboard|verification_report|verification_screenshots)' .gitignore
```

If still present, edit them out manually.

- [ ] **Step 6: Commit**

```bash
git add -A docs/samples/ .gitignore
git commit -m "chore: relocate verification artifacts to docs/samples (already gitignored)"
```

---

## Task 4: Create example config templates

**Files:**
- Create: `src/main/resources/application-local.yml.example`
- Create: `src/main/resources/application-prod.yml.example`

- [ ] **Step 1: Write `application-local.yml.example`** (dev/CI shape — non-empty values OK for localhost dev DB)

```yaml
# Copy to application-local.yml and fill in real values.
# application-local.yml is in .gitignore — never commit it.
#
# Active under: --spring.profiles.active=local  (default in restart.sh if you switch it)

spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/wucai_trade?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:}              # fill in dev DB password
  jpa:
    show-sql: true                         # verbose SQL is fine for local

app:
  jwt:
    # dev-only HS256 secret — generate with: openssl rand -base64 48
    secret: ${JWT_SECRET:dev-only-do-not-use-in-prod-replace-with-openssl-rand-base64-48-bytes}

ai:
  minimax:
    enabled: false                         # no real API call in local
  sensenova:
    enabled: false
  tavily:
    enabled: false

notification:
  serverchan:
    enabled: false
  wish-pool:
    enabled: false
```

- [ ] **Step 2: Write `application-prod.yml.example`** (shape for ops — actual values come from K8s Secret / vault, NOT committed)

```yaml
# Copy to application-prod.yml on the prod server (or mount via K8s Secret).
# application-prod.yml is in .gitignore — never commit it.
#
# All values MUST be supplied via env vars in production. This file only lists the
# property paths; the file is intentionally empty of real values.
#
# Required env vars (set in systemd unit / k8s Secret):
#   DB_USERNAME, DB_PASSWORD, JWT_SECRET
#   TDX_API_KEY, WIND_SKILL_DIR, WIND_CONFIG_PATH
#   SERVER_CHAN_SEND_KEY, WISH_POOL_FEISHU_WEBHOOK_URL
#   AI_MINIMAX_KEY, AI_SENSENOVA_KEY (or SENSENOVA_API_KEY), TAVILY_API_KEY
#   HUAXIN_SMS_USER, HUAXIN_SMS_PASSWORD, HUAXIN_SMS_PRODUCT_ID
#   WECHAT_APP_ID, WECHAT_APP_SECRET, WECHAT_REDIRECT_URI
#   WECHAT_MP_APP_ID, WECHAT_MP_APP_SECRET, WECHAT_MP_CALLBACK_URL, WECHAT_MP_CALLBACK_TOKEN
#
# To enable DB SSL, set DB_USE_SSL=true AND ensure MySQL server has SSL enabled
# AND a CA cert is mounted at /etc/ssl/mysql-ca.pem (path overridable via DB_SSL_CA_PATH).
DB_USE_SSL: ${DB_USE_SSL:false}
DB_SSL_CA_PATH: ${DB_SSL_CA_PATH:}

server:
  compression:
    enabled: true
    mime-types: text/html,text/css,application/javascript,application/json
    min-response-size: 1024

spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50
          order_inserts: true
          order_updates: true
          batch_versioned_data: true
```

- [ ] **Step 3: Verify both files exist and contain no real secret**

```bash
grep -E '(wmq534|tdx-c62|SCT354970|sk-cp-E09|sk-tNFEPGZZ|tvly-dev-6Rg1)' \
  src/main/resources/application-local.yml.example \
  src/main/resources/application-prod.yml.example
```

Expected: no output (zero matches). If anything prints, the example file leaked a secret — stop, scrub, re-commit.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application-local.yml.example src/main/resources/application-prod.yml.example
git commit -m "docs: add application-{local,prod}.yml.example templates"
```

---

## Task 5: Strip hardcoded secrets from `application.yml`

**Files:**
- Modify: `src/main/resources/application.yml`

This is the load-bearing change. Every line below replaces an inline default with an empty default `${X:}` so a missing env var is impossible to miss in startup logs.

- [ ] **Step 1: Strip DB password**

Locate the DB password line in `src/main/resources/application.yml` with a prefix grep (don't paste the full secret here):

```bash
grep -nE '^\s*password: \$\{DB_PASSWORD:' src/main/resources/application.yml
```

The matched line should currently look like:

```yaml
    password: ${DB_PASSWORD:<LEAKED>}
```

Replace it with:

```yaml
    password: ${DB_PASSWORD:}
```

- [ ] **Step 2: Strip JWT secret**

Line 61:

```yaml
    secret: ${JWT_SECRET:change-this-secret-key-in-production-please}
```

to:

```yaml
    # IMPORTANT: empty default; runtime fails-fast if profile != local (see StartupConfigValidator)
    secret: ${JWT_SECRET:}
```

- [ ] **Step 3: Strip TDX API key**

Locate the TDX API key line in `src/main/resources/application.yml` with a prefix grep (don't paste the full secret here):

```bash
grep -nE '^\s*api-key: TDX-' src/main/resources/application.yml
```

The matched line should currently look like:

```yaml
    api-key: TDX-<LEAKED>
```

Replace it with:

```yaml
    api-key: ${TDX_API_KEY:}
```

- [ ] **Step 4: Strip Server酱 send key**

Locate the Server酱 send-key line in `src/main/resources/application.yml` with a prefix grep (don't paste the full secret here):

```bash
grep -nE '^\s*send-key: \$\{SERVER_CHAN_SEND_KEY:' src/main/resources/application.yml
```

The matched line should currently look like:

```yaml
    send-key: ${SERVER_CHAN_SEND_KEY:<LEAKED>}
```

Replace it with:

```yaml
    send-key: ${SERVER_CHAN_SEND_KEY:}
```

- [ ] **Step 5: Strip Feishu wish-pool webhook**

Locate the wish-pool webhook line in `src/main/resources/application.yml` with a prefix grep (don't paste the full secret here):

```bash
grep -nE '^\s*webhook-url: \$\{WISH_POOL_FEISHU_WEBHOOK_URL:' src/main/resources/application.yml
```

The matched line should currently look like:

```yaml
    webhook-url: ${WISH_POOL_FEISHU_WEBHOOK_URL:<LEAKED>}
```

Replace it with:

```yaml
    webhook-url: ${WISH_POOL_FEISHU_WEBHOOK_URL:}
```

- [ ] **Step 6: Strip MiniMax API key**

Locate the MiniMax API key line in `src/main/resources/application.yml` with a prefix grep (don't paste the full secret here):

```bash
grep -nE '^\s*api-key: \$\{AI_MINIMAX_KEY:' src/main/resources/application.yml
```

The matched line should currently look like:

```yaml
    api-key: ${AI_MINIMAX_KEY:<LEAKED>}
```

Replace it with:

```yaml
    api-key: ${AI_MINIMAX_KEY:}
```

- [ ] **Step 7: Strip SenseNova API key (and the nested alias)**

Locate the SenseNova API key line in `src/main/resources/application.yml` with a prefix grep (don't paste the full secret here):

```bash
grep -nE '^\s*api-key: \$\{SENSENOVA_API_KEY:' src/main/resources/application.yml
```

The matched line should currently look like:

```yaml
    api-key: ${SENSENOVA_API_KEY:${AI_SENSENOVA_KEY:<LEAKED>}}
```

Replace it with:

```yaml
    api-key: ${SENSENOVA_API_KEY:${AI_SENSENOVA_KEY:}}
```

- [ ] **Step 8: Strip Tavily API key**

Locate the Tavily API key line in `src/main/resources/application.yml` with a prefix grep (don't paste the full secret here):

```bash
grep -nE '^\s*api-key: tvly-' src/main/resources/application.yml
```

The matched line should currently look like:

```yaml
    api-key: tvly-<LEAKED>
```

Replace it with:

```yaml
    api-key: ${TAVILY_API_KEY:}
```

- [ ] **Step 9: Verify no leaked substrings remain**

```bash
grep -nE '(wmq534|tdx-c62|SCT354970|sk-cp-E09|sk-tNFEPGZZ|tvly-dev-6Rg1|a4c6882a-b4f9|change-this-secret)' src/main/resources/application.yml
```

Expected: zero matches. If any line prints, undo that step and redo.

- [ ] **Step 10: Re-grep the original inventory list (Task 1 Step 1) to confirm only `:}` suffixes remain**

```bash
grep -nE '(password|secret|key|token|webhook)[^:]*:' src/main/resources/application.yml \
  | grep -vE '^\s*#|no-|disabled|allowed|exclude-filter|expire|cooldown|code-id|mock|cache|public-id'
```

Expected: every line ends with a `${ENV_VAR:}` (empty) — no inline defaults left.

- [ ] **Step 11: Commit (DO NOT deploy yet — this alone breaks prod without Task 8)**

```bash
git add src/main/resources/application.yml
git commit -m "fix(security): remove hardcoded secret defaults from application.yml"
```

---

## Task 6: Gzip static responses (Quick Win #3)

**Files:**
- Modify: `src/main/resources/application.yml`

This is the cheap perf-side cleanup from the user's Quick Wins list, fits in Sprint 1 because it's the same file as Task 5.

- [ ] **Step 1: Append the compression block**

After the `spring:` block (after line 54, before the next top-level key `app:` on line 55), insert:

```yaml
server:
  compression:
    enabled: true
    mime-types: text/html,text/css,application/javascript,application/json
    min-response-size: 1024
```

Note: there is already a `server:` block at the top of the file (line 1: `server: / port: 8080 / servlet: ...`). Spring Boot does NOT allow two top-level `server:` keys in one YAML file — merge the compression block under the existing `server:` block instead.

Edit the existing block at line 1 so it becomes:

```yaml
server:
  port: 8080
  servlet:
    context-path: /gp
    encoding:
      charset: UTF-8
      force: true
  compression:
    enabled: true
    mime-types: text/html,text/css,application/javascript,application/json
    min-response-size: 1024
```

- [ ] **Step 2: Verify exactly one `server:` block exists**

```bash
grep -nE '^server:' src/main/resources/application.yml
```

Expected: prints exactly one line (around line 1).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "perf: enable gzip compression for static responses"
```

---

## Task 7: DB SSL toggle (gated, won't break prod unless enabled)

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/quant/config/DataSourceUrlCustomizer.java` (NEW)

This is gated on `DB_USE_SSL` env var defaulting to `false` (current behavior) so accidental flip doesn't cause prod outage. Set `DB_USE_SSL=true` on servers where MySQL has SSL configured.

- [ ] **Step 1: Add a `Java system properties` block that respects DB_USE_SSL**

In `application.yml`, immediately after line 12 (`url: jdbc:mysql://43.140.208.165:3306/wucai_trade?...&useSSL=false&allowPublicKeyRetrieval=true`), insert a placeholder so a future commit can flip without rewriting the URL. The cleanest approach is a small Spring `EnvironmentPostProcessor` that rewrites the URL before the DataSource bean builds.

Create `src/main/java/com/quant/config/DataSourceUrlCustomizer.java`:

```java
package com.quant.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Rewrites spring.datasource.url to enable SSL when DB_USE_SSL=true.
 * Idempotent — does nothing in local/dev/test where DB_USE_SSL unset.
 *
 * MySQL server MUST have SSL enabled for useSSL=true to connect.
 * See docs/superpowers/plans/2026-07-15-secret-inventory.md.
 */
public class DataSourceUrlCustomizer implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        boolean sslOn = Boolean.parseBoolean(env.getProperty("DB_USE_SSL", "false"));
        if (!sslOn) return;

        String currentUrl = env.getProperty("spring.datasource.url");
        if (currentUrl == null || currentUrl.isBlank() || currentUrl.contains("useSSL=true")) return;

        String rewritten = currentUrl
                .replace("useSSL=false", "useSSL=true&requireSSL=true")
                .replace("allowPublicKeyRetrieval=true&", "")
                .replace("&allowPublicKeyRetrieval=true", "")
                .replace("?allowPublicKeyRetrieval=true&", "?");

        Map<String, Object> map = new HashMap<>();
        map.put("spring.datasource.url", rewritten);
        env.getPropertySources().addFirst(new MapPropertySource("dbSslCustomizer", map));
    }
}
```

- [ ] **Step 2: Register the processor in `META-INF/spring.factories`**

Spring Boot 3 uses `META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports`. Create the file:

```bash
mkdir -p src/main/resources/META-INF/spring
```

Create `src/main/resources/META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports`:

```
com.quant.config.DataSourceUrlCustomizer
```

(Boot 3 dropped the classic `.factories` file for environment post-processors — use the `.imports` file. Spring auto-discovers both, but the `.imports` form is correct for 3.x.)

- [ ] **Step 3: Verify the file is on classpath**

```bash
ls src/main/resources/META-INF/spring/
```

Expected: shows `org.springframework.boot.env.EnvironmentPostProcessor.imports`.

- [ ] **Step 4: Don't change the URL in `application.yml` yet**

Keep line 13 (`useSSL=false&allowPublicKeyRetrieval=true`) exactly as-is. The customizer only flips it when `DB_USE_SSL=true`. Until ops confirms MySQL has SSL, the runtime behavior is identical to today.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/quant/config/DataSourceUrlCustomizer.java src/main/resources/META-INF/spring/
git commit -m "feat(security): gated DB SSL toggle via DB_USE_SSL env var"
```

---

## Task 8: Fail-fast config validator

**Files:**
- Create: `src/main/java/com/quant/config/StartupConfigValidator.java`

Without this, a missing env var silently degrades security: e.g. an empty JWT secret still produces tokens (using HS256 with empty key material) that any attacker can forge. This guard makes prod refuse to start instead.

- [ ] **Step 1: Write the test-first check**

Before writing the validator, write a test that asserts a missing `JWT_SECRET` in prod profile fails the ApplicationContext refresh.

Create `src/test/java/com/quant/config/StartupConfigValidatorTest.java`:

```java
package com.quant.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertThrows;

class StartupConfigValidatorTest {

    @Test
    void failsWhenJwtSecretBlankInProdProfile() {
        // minimal context — only SecurityConfig + our validator + a placeholder AuthProperties
        // (the real production app uses @SpringBootApplication; we narrow to surface the guard)
        SpringApplicationBuilder builder = new SpringApplicationBuilder(StartupConfigValidator.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.profiles.active=prod",
                        "spring.main.web-application-type=none",
                        "spring.autoconfigure.exclude=" +
                                "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
                        "app.jwt.secret=");
        assertThrows(Exception.class, builder::run);
    }
}
```

- [ ] **Step 2: Run the test — expect failure**

```bash
mvn test -Dtest=StartupConfigValidatorTest
```

Expected: FAIL — class `StartupConfigValidator` doesn't exist yet. The error should mention "Unable to find @SpringBootConfiguration" or the missing bean. That's the RED state.

- [ ] **Step 3: Implement `StartupConfigValidator`**

Create `src/main/java/com/quant/config/StartupConfigValidator.java`:

```java
package com.quant.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Validates that secrets required in non-local profiles are non-empty.
 * Runs at @Order(0) so it fails the app boot before anything else binds.
 *
 * Local profile is exempt — devs run without prod secrets.
 *
 * To extend: add another required-secret check in the same fail-fast shape.
 */
@Component
@org.springframework.core.annotation.Order(0)
@RequiredArgsConstructor
public class StartupConfigValidator implements ApplicationRunner {

    private final Environment env;

    @Override
    public void run(ApplicationArguments args) {
        if (env.acceptsProfiles(Profiles.of("local"))) return;

        require("spring.datasource.password", "DB_PASSWORD");
        require("app.jwt.secret",            "JWT_SECRET");
        require("ai.minimax.api-key",         "AI_MINIMAX_KEY");
        require("ai.sensenova.api-key",       "SENSENOVA_API_KEY or AI_SENSENOVA_KEY");
        require("ai.tavily.api-key",          "TAVILY_API_KEY");
        require("prosperity-strong.tdx.api-key", "TDX_API_KEY");
        require("notification.serverchan.send-key", "SERVER_CHAN_SEND_KEY");
        require("notification.wish-pool.webhook-url", "WISH_POOL_FEISHU_WEBHOOK_URL");
    }

    private void require(String key, String friendlyEnvVar) {
        String value = env.getProperty(key, "");
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required configuration is missing or empty: " + key +
                    " (set env var " + friendlyEnvVar + "). " +
                    "Refusing to start in profile '" + String.join(",", env.getActiveProfiles()) + "'.");
        }
    }
}
```

- [ ] **Step 4: Run the test — expect pass**

```bash
mvn test -Dtest=StartupConfigValidatorTest
```

Expected: PASS — the validator throws `IllegalStateException`, the test catches it. If using `assertThrows(IllegalStateException.class, ...)`, narrow the assertion; the broad `Exception.class` will pass either way.

- [ ] **Step 5: Tighten the assertion**

Edit the test to assert the specific exception type, so future contributors don't accidentally weaken the contract:

```java
import static org.junit.jupiter.api.Assertions.assertThrows;
// ...
assertThrows(IllegalStateException.class, () -> builder.profiles("prod").run());
```

Actually — the test above uses `.properties("spring.profiles.active=prod")` already, so the assertion just needs the type:

```java
assertThrows(IllegalStateException.class, () -> builder.run());
```

- [ ] **Step 6: Run again**

```bash
mvn test -Dtest=StartupConfigValidatorTest
```

Expected: PASS with the narrower assertion.

- [ ] **Step 7: Run the FULL test suite to confirm no regression**

```bash
mvn test
```

Expected: all 70-ish pre-existing tests + the new one pass. If SchemaInitializer / DataSource-related tests fail because they relied on `useSSL=false` defaults, they still pass (we did NOT change the default).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/quant/config/StartupConfigValidator.java src/test/java/com/quant/config/StartupConfigValidatorTest.java
git commit -m "feat(security): fail-fast startup validator for missing secrets in non-local profiles"
```

---

## Task 9: Local-convenience default — point to `application-local.yml` when no profile set

**Files:**
- Modify: `restart.sh`
- Modify: `pom.xml` (NO — skip; restart.sh already supports `--spring.profiles.active=default`, just change the default)

The deployed app currently launches with `--spring.profiles.active=default`. After Sprint 1 ships, prod will fail to start until ops either (a) exports env vars in systemd unit, OR (b) mounts an `application-prod.yml`. We can't mandate (b) from inside the repo, but we can make (a) easy by sourcing `/etc/gupiao-quant/secrets.env` if it exists.

- [ ] **Step 1: Add an env-file sourcing step before the `java -jar` invocation**

In `restart.sh`, immediately before line 156 (`nohup java -Xmx512m ...`), insert:

```bash
# ============================================================
# 4.5 加载运维密钥文件（可选；不存在则不报错，使用 application.yml 中的 ${ENV:} 占位）
# ============================================================
SECRETS_FILE="/etc/gupiao-quant/secrets.env"
if [ -f "$SECRETS_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    . "$SECRETS_FILE"
    set +a
    echo "      ✓ 已加载运维密钥文件: $SECRETS_FILE"
else
    echo "      (未找到 $SECRETS_FILE, 沿用 application.yml 占位)"
fi
```

- [ ] **Step 2: Update the active profile to `prod` instead of `default`**

In `restart.sh` line 157, change:

```bash
    --spring.profiles.active=default \
```

to:

```bash
    --spring.profiles.active=prod \
```

This makes Spring activate `application-prod.yml` if mounted (via Spring Boot's `application-{profile}.yml` resolution rules). In its absence, the validator from Task 8 fires and the app fails to boot — which is the right behavior.

- [ ] **Step 3: Document the deploy contract**

Create `deploy/secrets.env.example` (a sibling, NOT under `src/main/resources`) so ops have a template:

```bash
# /etc/gupiao-quant/secrets.env — referenced by restart.sh, never committed.

# 数据库
export DB_USERNAME='wucai_app'
export DB_PASSWORD='<rotate-me>'

# 认证
export JWT_SECRET='<rotate-me — openssl rand -base64 48>'

# TDX
export TDX_API_KEY='<rotate-me>'

# Server酱 + 飞书
export SERVER_CHAN_SEND_KEY='<rotate-me>'
export WISH_POOL_FEISHU_WEBHOOK_URL='<rotate-me>'

# AI
export AI_MINIMAX_KEY='<rotate-me>'
export AI_SENSENOVA_KEY='<rotate-me>'
export TAVILY_API_KEY='<rotate-me>'

# 可选 DB SSL
export DB_USE_SSL=true
export DB_SSL_CA_PATH=/etc/ssl/mysql-ca.pem
```

- [ ] **Step 4: Add `deploy/` to `.gitignore`** (avoid future foot-guns)

Append to `.gitignore` under the existing secrets section:

```gitignore
deploy/.env
deploy/*.env
```

- [ ] **Step 5: Commit**

```bash
git add restart.sh deploy/secrets.env.example .gitignore
git commit -m "feat(deploy): source /etc/gupiao-quant/secrets.env and activate prod profile in restart.sh"
```

---

## Task 10: Document the manual rotation work

**Files:**
- Create: `docs/superpowers/plans/2026-07-15-secret-rotation-checklist.md`

Sprint 1 reduces future leakage but cannot retroactively remove values from git history. The user must rotate every leaked credential — that's a manual checklist.

- [ ] **Step 1: Write the rotation checklist**

Create `docs/superpowers/plans/2026-07-15-secret-rotation-checklist.md`:

```markdown
# Manual Secret Rotation Checklist — for 东哥

Sprint 1 stops further leakage but does NOT rewrite git history. The values in
`docs/superpowers/plans/2026-07-15-secret-inventory.md` are still in `git log`.
**Treat them as compromised and rotate.**

For each item: regenerate → set in `/etc/gupiao-quant/secrets.env` on the prod server
→ restart (`./restart.sh`) → verify the app's relevant module still works → check
this box.

If repo history rewrite is desired instead, run `git filter-repo` per credential,
force-push, and notify all collaborators to re-clone. That is a heavier hammer;
prefer rotation unless you have a specific reason.

## Rotation actions

- [ ] **DB password** (`DB_PASSWORD`)
  - mysql: `ALTER USER 'wucai_app'@'%' IDENTIFIED BY '<new-strong-32+chars>'; FLUSH PRIVILEGES;`
  - update `/etc/gupiao-quant/secrets.env`
  - verify: `curl http://localhost:8080/gp/api/stock-analysis/health`

- [ ] **JWT secret** (`JWT_SECRET`)
  - generate: `openssl rand -base64 48`
  - **rotation invalidates all existing user tokens** — broadcast a logout notice before restart
  - update env, restart, verify by logging in fresh

- [ ] **MiniMax API key** (`AI_MINIMAX_KEY`)
  - log in at minimaxi.com → API → rotate key
  - update env, restart, verify by hitting any `/api/prosperity-strong/**` endpoint that triggers an LLM call

- [ ] **SenseNova API key** (`SENSENOVA_API_KEY` / `AI_SENSENOVA_KEY`)
  - rotate at token.sensenova.cn → API keys
  - verify via `/api/stock-analysis/health` and a sample analysis request

- [ ] **Tavily API key** (`TAVILY_API_KEY`)
  - rotate at tavily.com → API keys
  - verify by triggering a news lookup

- [ ] **Server酱 send key** (`SERVER_CHAN_SEND_KEY`)
  - rotate at sct.ftqq.com → 密钥管理
  - verify by faking a notification (use a test endpoint or wait for the next intraday alert)

- [ ] **飞书 wish-pool webhook** (`WISH_POOL_FEISHU_WEBHOOK_URL`)
  - delete the old bot in 飞书群 → 添加机器人 → copy the new webhook URL
  - verify by submitting a wish via the public UI

- [ ] **TDX API key** (`TDX_API_KEY`)
  - rotate via TDX 控制台
  - verify `/api/prosperity-strong/...` realtime flow

- [ ] **MySQL server SSL cert** (optional, only if enabling `DB_USE_SSL=true`)
  - generate / obtain a CA-signed cert for the MySQL server
  - configure `require_secure_transport=ON` server-side
  - mount CA cert at `/etc/ssl/mysql-ca.pem` on the app server
  - set `DB_USE_SSL=true` in `/etc/gupiao-quant/secrets.env`
  - restart, verify by `mysql --ssl -h 43.140.208.165 -u ... -e 'SHOW STATUS LIKE "Ssl_cipher";'`
  - **DO NOT** flip `DB_USE_SSL=true` if the MySQL server doesn't have SSL configured — the connection will fail.

## After rotation

- [ ] Update `docs/superpowers/plans/2026-07-15-secret-inventory.md` with new values (or replace the values column with `[REDACTED — rotated YYYY-MM-DD]`).

- [ ] Consider `git filter-repo --invert-paths --path application.yml` if you want to scrub history. This rewrites all commits and is a non-trivial disruption for anyone with the repo.
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/plans/2026-07-15-secret-rotation-checklist.md
git commit -m "docs: add manual secret rotation checklist for Sprint 1 leaked credentials"
```

---

## Task 11: README security section + deploy runbook

**Files:**
- Modify: `README.md`
- Create: `deploy/README.md`

- [ ] **Step 1: Add a "Security & Secrets" section to `README.md`**

Append at the end of `README.md`:

```markdown
## 安全与密钥

> ⚠️  本项目强制要求：所有真实密钥必须通过环境变量传入，禁止落到仓库。

### 本地开发

```bash
cp src/main/resources/application-local.yml.example \
   src/main/resources/application-local.yml
# 编辑 application-local.yml，填入本地 DB 密码 / dev API key
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### 生产部署

将所有运维密钥放在 `/etc/gupiao-quant/secrets.env`（详见 `deploy/secrets.env.example`）。
`restart.sh` 会自动 source 该文件，并把 Spring profile 切到 `prod`。
任何缺失的密钥会让启动直接失败（见 `StartupConfigValidator`），而不是静默降级。

### 已泄漏密钥轮换

参见 `docs/superpowers/plans/2026-07-15-secret-rotation-checklist.md`。
```

- [ ] **Step 2: Create `deploy/README.md`**

```markdown
# Deploy runbook

## 一次性配置

```bash
# 1. 准备密钥文件
sudo mkdir -p /etc/gupiao-quant
sudo cp deploy/secrets.env.example /etc/gupiao-quant/secrets.env
sudo chmod 600 /etc/gupiao-quant/secrets.env
sudo vim /etc/gupiao-quant/secrets.env   # 填入真实密钥

# 2. （可选）DB SSL 时，准备 CA 证书
sudo mkdir -p /etc/ssl
sudo cp <your-ca-cert>.pem /etc/ssl/mysql-ca.pem

# 3. 部署代码 + 启动
./restart.sh
```

## 启动失败排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `IllegalStateException: Required configuration is missing...` | env var 未传入 | 编辑 `/etc/gupiao-quant/secrets.env` 后 `./restart.sh` |
| `Communications link failure ... SSL ...` | DB_USE_SSL=true 但 MySQL 未启用 SSL | 改回 `DB_USE_SSL=false` 或先在 MySQL 侧启用 |
| `401 Unauthorized` 全部变多 | JWT_SECRET 轮换导致旧 token 失效 | 让用户重新登录；JWT 不可平滑轮换 |
| gzip 不生效 | 浏览器 `Accept-Encoding` 缺失 | 抓包确认 `Content-Encoding: gzip` 头 |
```

- [ ] **Step 3: Commit**

```bash
git add README.md deploy/README.md
git commit -m "docs: add README security section and deploy runbook"
```

---

## Task 12: End-to-end verification

- [ ] **Step 1: Run the full test suite**

```bash
mvn test
```

Expected: all tests pass, including `StartupConfigValidatorTest`.

- [ ] **Step 2: Build the jar locally without deploying**

```bash
mvn clean package -DskipTests
```

Expected: BUILD SUCCESS, jar at `target/gupiao-quant-1.0.0.jar`.

- [ ] **Step 3: Boot the app locally with profile `local` and an empty `application-local.yml`**

```bash
echo > src/main/resources/application-local.yml   # blank is fine for local profile — validator exempts it
nohup java -jar target/gupiao-quant-1.0.0.jar --spring.profiles.active=local > /tmp/sprint1-test.log 2>&1 &
echo $! > /tmp/sprint1-test.pid
```

Wait ~30s. Then:

```bash
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8080/gp/
```

Expected: HTTP 200 (or 302 if the root redirects to login).

- [ ] **Step 4: Boot the app locally with profile `prod` and NO secrets env — should refuse to start**

```bash
kill $(cat /tmp/sprint1-test.pid) 2>/dev/null
sleep 2
nohup java -jar target/gupiao-quant-1.0.0.jar --spring.profiles.active=prod > /tmp/sprint1-prod.log 2>&1 &
sleep 8
grep -E "Required configuration is missing" /tmp/sprint1-prod.log
```

Expected: at least one `Required configuration is missing` line. The app should NOT respond on port 8080:

```bash
curl -s -o /dev/null -w "HTTP %{http_code}\n" --max-time 3 http://localhost:8080/gp/
```

Expected: `000` (curl can't connect — app died on startup).

- [ ] **Step 5: Smoke-test gzip**

With the `local`-profile instance still running:

```bash
curl -sI -H "Accept-Encoding: gzip" http://localhost:8080/gp/static/css/some.css | grep -i content-encoding
```

(or hit any HTML). Expected: `Content-Encoding: gzip`.

- [ ] **Step 6: Kill the local instance**

```bash
kill $(cat /tmp/sprint1-test.pid) 2>/dev/null
rm -f /tmp/sprint1-test.pid /tmp/sprint1-test.log /tmp/sprint1-prod.log
```

- [ ] **Step 7: Confirm zero leaked substrings in the committed `application.yml`**

```bash
grep -nE '(wmq534|tdx-c62|SCT354970|sk-cp-E09|sk-tNFEPGZZ|tvly-dev-6Rg1|a4c6882a-b4f9|change-this-secret)' \
  src/main/resources/application.yml
```

Expected: no output.

- [ ] **Step 8: Confirm `restart.sh` references `prod` and secrets.env sourcing**

```bash
grep -nE 'spring.profiles.active=|secrets.env' restart.sh
```

Expected: shows `--spring.profiles.active=prod` and `secrets.env` lines.

- [ ] **Step 9: Final commit (only if any of the verifications surfaced a tweak)**

If everything passed, skip. Otherwise commit fixes; this is the last commit of Sprint 1.

```bash
git status
# if anything to add:
git add -A && git commit -m "fix(sprint-1): final verification tidy-ups"
```

---

## Self-review checklist (run before declaring Sprint 1 done)

- [ ] `application.yml` has zero inline defaults — only `${ENV_VAR:}` placeholders.
- [ ] `.gitignore` excludes `application-{prod,dev,staging}.{yml,yaml}` and the three verification artifacts have moved.
- [ ] `application-local.yml.example` and `application-prod.yml.example` are the only committed config files.
- [ ] `StartupConfigValidator` runs at `@Order(0)` and refuses to start when any of the 8 secret keys is empty in non-local profiles.
- [ ] `restart.sh` sources `/etc/gupiao-quant/secrets.env` and activates `--spring.profiles.active=prod`.
- [ ] `deploy/secrets.env.example` exists; `deploy/README.md` documents the contract.
- [ ] `mvn test` passes (the new `StartupConfigValidatorTest` plus all 69 existing tests).
- [ ] Locally, app boots in `--spring.profiles.active=local`, refuses to boot in `--spring.profiles.active=prod` without env vars.
- [ ] Gzip confirmed on at least one static asset.
- [ ] `docs/superpowers/plans/2026-07-15-secret-inventory.md` and `2026-07-15-secret-rotation-checklist.md` exist; rotation is on 东哥's plate.
- [ ] No real secret value exists anywhere in the committed tree (run the broad greps one more time before declaring done).

---

## Out of scope for Sprint 1 (queued for subsequent plans)

- **Sprint 2** — Flyway migration, package-by-feature, god-class splits, Spotless/Checkstyle/SpotBugs.
- **Sprint 3** — JPA batching, Hikari pool size, unified HTTP client (`HttpClients` config), full gzip/ETag/Cache-Control headers, drop webflux if possible.
- **Sprint 4** — JaCoCo, Testcontainers, frontend bundling, move `ai-compute-dashboard.html` follow-up.
- **Sprint 5** — Prometheus/Micrometer, `restart.sh` → `build.sh` + `deploy.sh`, README/CHANGELOG sync, `~/.m2/settings.xml` audit.

Each gets its own plan file under `docs/superpowers/plans/`.
