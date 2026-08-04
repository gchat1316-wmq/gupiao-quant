# gupiao-quant 项目指南

> 本文件面向 AI 编码助手。如果你刚拿到这个项目，请先阅读本文件，而不是 README.md——README.md 已经严重滞后，只描述了项目最初的股票财务查询功能，没有覆盖后续新增的强势股选股、学习搭子、龙江投资、科技 AI 监控等模块。

## 1. 项目概述

`gupiao-quant`（股票量化）是一个基于 Spring Boot 的投资工具平台，已经从单一的股票财务数据对比演化为多个子系统：

- **股票财务查询**：多只股票核心财务指标对比（毛利率、营收同比、扣非同比、扣非 TTM）和 Chart.js 折线图。
- **学习搭子（Study Buddy）**：课程、知识点、卡片、测验、学习资料上传与 AI 详解，配套完整 H5 前端。
- **龙江投资 / 股票池**：自定义股票池、仓位填充、估值计算、SOP 检查、OCR 批量导入。
- **热点选股**：盘后扫描热门板块 → 识别领涨股 → 财务硬过滤 → 主线评估 → 生成候选池与仓位建议。
- **个股分析（紫苏叶/九维）**：调用外部 Python 脚本生成多维分析报告。
- **科技 AI 实时行情监控**：对接 QMT/xtdata 写入实时行情，策略告警与持仓建议。
- **每日市场复盘**：生成结构化市场回顾报告。
- **许愿池**：通过飞书 webhook 接收用户心愿/反馈。

项目目前部署在 `https://aidaily.dpdns.org/gp/`，本地开发端口 `8080`，context-path 为 `/gp`。

## 2. 技术栈

| 层级 | 技术 |
| --- | --- |
| 后端框架 | Spring Boot 3.2.5 |
| JDK | Java 17 |
| 数据访问 | Spring Data JPA + Hibernate + MySQL 8 |
| 缓存 | Spring Cache + Caffeine |
| 前端 | 原生 HTML5 / CSS3 / 原生 JS + Chart.js 4（CDN） |
| 工具库 | Lombok、PDFBox、Flexmark、Jackson |
| 构建工具 | Maven |
| 外部数据/AI | BaoStock、东方财富、Wind MCP、QMT/xtdata、MiniMax、SenseNova、Tavily、ServerChan、飞书 webhook |

## 3. 目录结构

```
gupiao-quant/
├── pom.xml                      # Maven 构建配置
├── restart.sh                   # 生产部署加固脚本
├── README.md                    # 已滞后，只描述旧功能
├── plan.md                      # 学习搭子 PRD
├── .gitignore                   # Git 忽略规则
├── sql/                         # 数据库初始化/变更脚本
│   ├── wucai_trade.sql          # 主库全量结构（当前使用的库）
│   ├── prosperity_strong_init.sql
│   ├── prosperity_strong_alter_v2.sql
│   ├── stock_analysis_init.sql
│   ├── tech_ai_alert_thresholds_alter.sql
│   └── tech_ai_position_alter.sql
├── scripts/                     # Python 辅助脚本
│   ├── baostock_basic_sync.py
│   ├── baostock_daily_sync.py
│   ├── baostock_latest_5m.py
│   ├── qmt_tech_ai_bridge.py
│   └── render_pdf.py
├── docs/                        # PRD 与原型截图（被 .gitignore 忽略）
├── uploads/                     # 用户上传文件（被 .gitignore 忽略）
├── src/main/java/com/quant/     # Java 后端源码
│   ├── GupiaoQuantApplication.java
│   ├── config/                  # 配置属性类
│   ├── controller/              # REST API
│   ├── dto/                     # 请求/响应 DTO（按模块分子包）
│   ├── entity/                  # JPA 实体
│   ├── repository/              # Spring Data Repository
│   └── service/                 # 业务服务层（含 ai/prosperitystrong/search/techai 子包）
├── src/main/resources/
│   ├── application.yml          # 所有运行时配置（含外部化环境变量）
│   └── static/                  # 前端静态资源（HTML/CSS/JS）
└── src/test/java/com/quant/     # 单元/集成测试
```

## 4. 构建与测试命令

### 本地开发启动

```bash
mvn spring-boot:run
```

访问：http://localhost:8080/gp/

### 打包

```bash
mvn clean package
```

产物：`target/gupiao-quant-1.0.0.jar`

### 运行测试

```bash
mvn test
```

测试框架：JUnit 5 + Mockito + AssertJ。`pom.xml` 中配置了 Surefire 参数 `-Dnet.bytebuddy.experimental=true`。

### 生产部署

**请始终使用项目根目录的 `restart.sh` 启动/重启**，不要手动 `nohup java -jar`。

```bash
./restart.sh
```

`restart.sh` 会完成：环境自检、磁盘预检、清理残留 baostock/playwright/java 进程、修复文件权限、`mvn clean package -DskipTests`、Git 漂移检测、启动 jar、本地端口健康检测、外部 URL 冒烟测试、失败时输出诊断包。

## 5. 代码风格与约定

- 编码：UTF-8，Java 17。
- 使用 Lombok：`@Data`、`@RequiredArgsConstructor`、`@Slf4j` 很常见。
- 包结构严格分层：`config`、`controller`、`dto.*`、`entity`、`repository`、`service.*`。
- 配置外部化：`application.yml` 中使用 `${ENV_VAR:default}` 模式，便于不同环境覆盖。
- 数据库连接、AI Key、通知 Key 等敏感配置应通过环境变量注入。
- 启动时自动执行 schema 初始化/加列：`SchemaInitializer`（`CommandLineRunner`，`@Order(1)`）。
- 定时任务使用 `@EnableScheduling`，cron 表达式统一写在 `application.yml` 中。
- 服务层建议按模块分子包：`service/prosperitystrong`、`service/techai`、`service/ai`、`service/search`。

## 6. 数据库与配置

### 当前数据库

`application.yml` 中连接的是 `wucai_trade`。表前缀按模块划分：

- `trade_*`：股票基础/日 K/财务数据
- `study_*`：学习搭子
- `invest_*`：投资池/仓位/告警
- `prosperity_*`：热点选股
- `stock_analysis_record`：个股分析
- `tech_ai_*`：科技 AI 实时行情

### 初始化脚本

新环境建表直接执行：

```bash
mysql -u<user> -p < sql/wucai_trade.sql
```

后续模块追加表/列，按需执行 `sql/` 下对应的 `*_init.sql` 或 `*_alter_*.sql`。

### 重要配置项（生产必须覆盖）

请在生产环境通过环境变量覆盖，不要依赖 `application.yml` 中的默认值：

- `DB_USERNAME`、`DB_PASSWORD`
- `AI_MINIMAX_KEY`、`SENSENOVA_API_KEY` / `AI_SENSENOVA_KEY`
- `SERVER_CHAN_SEND_KEY`
- `WISH_POOL_FEISHU_WEBHOOK_URL`
- `WIND_SKILL_DIR`、`WIND_CONFIG_PATH`
- `TDX_CONNECTOR_DIR`、`TDX_MCP_URL`

## 7. 测试策略

- 单元测试集中在 `src/test/java/com/quant/`，按模块分子包。
- 服务层测试使用 Mockito  mock Repository/外部客户端。
- `sop/` 包下是标准操作流程的集成/端到端测试。
- 添加新功能时，请为对应的服务层补充单元测试。
- 运行 `mvn test` 后可在 `target/surefire-reports/` 查看详细报告。

## 8. 部署与运维

- 生产入口脚本：`restart.sh`。
- 运行后 PID 写入 `run.pid`，日志写入 `app.log`。
- 健康检查接口：`GET /gp/api/stock-analysis/health`
- 内部首页：`http://localhost:8080/gp/`
- 外部首页：`https://aidaily.dpdns.org/gp/`
- 停服：`kill $(cat run.pid)`

### 部署注意事项

1. `restart.sh` 会先清理占用 8080 端口的进程，确保端口释放。
2. 如果 jar 的修改时间比当前运行进程更新，脚本会自动杀掉旧进程消除漂移。
3. 脚本会检测未提交/未推送的 Git 改动并告警，但不会自动提交或推送。
4. 磁盘使用率 ≥ 90% 时，脚本会自动清理大于 100MB 的历史日志文件。

## 9. 安全注意事项

> ⚠️ **当前代码中存在硬编码的 API Key/Token/数据库密码**，主要分布在 `application.yml` 中（MiniMax、SenseNova、Tavily、ServerChan、飞书 webhook 等）。

- **生产部署前必须将这些敏感值改为环境变量注入**，不要提交真实密钥。
- `.gitignore` 已经屏蔽了 `.env`、`*env.*`、`application-local.yml`、`secrets/`、`uploads/`、`docs/`、`app.log.*`、`run.pid`，但 `application.yml` 仍在版本控制中。
- 不要在前端代码中暴露后端 API Key 或数据库连接信息。
- 数据库连接当前指向公网 IP，生产环境建议限制访问源 IP 并使用 SSL。

## 10. 常见操作速查

| 操作 | 命令 |
| --- | --- |
| 本地启动 | `mvn spring-boot:run` |
| 运行测试 | `mvn test` |
| 打包 | `mvn clean package` |
| 生产启动 | `./restart.sh` |
| 查看日志 | `tail -f app.log` |
| 停止服务 | `kill $(cat run.pid)` |
| 健康检查 | `curl http://localhost:8080/gp/api/stock-analysis/health` |
| 初始化数据库 | `mysql -u<user> -p < sql/wucai_trade.sql` |

## 11. 给 AI 助手的特别提示

1. **README.md 已过期**：做任何改动后，不要以 README.md 作为唯一参考，应以 `application.yml`、实际源码、`sql/` 脚本和本文件为准。
2. **新增模块请保持分包一致性**：controller → service（必要时建子包）→ dto/entity/repository，前端页面放在 `src/main/resources/static/`。
3. **涉及数据库变更**：请同时提供 `sql/` 下的增量脚本，并在 `SchemaInitializer` 中考虑自动执行逻辑。
4. **涉及敏感配置**：新增 API Key 或 webhook 时，必须写成 `${ENV_VAR:default}` 形式，default 值应为占位符或本地调试值。
5. **修改后请运行测试**：`mvn test`，确保没有破坏现有功能。

## Cursor Cloud specific instructions

面向在 Cloud VM 中运行的后续 agent。工具链（JDK 17 为默认 `java`、Maven、MySQL 8）已随快照安装，启动脚本只做依赖刷新，服务需手动启动。以下都是不显而易见、容易踩坑的点：

- **先启动 MySQL 再跑应用**：`sudo service mysql start`。数据库 `wucai_trade` 与一个可 TCP 登录的账号（`root` / `root`，覆盖 `localhost`/`127.0.0.1`/`%`）已在快照里建好；应用通过 JDBC 连 `127.0.0.1:3306`，而 MySQL 默认的 `root@localhost` 是 socket 认证，无法用于 TCP，所以务必用这个账号（或按需重建）。
- **本地开发用 local profile**：`mvn spring-boot:run -Dspring-boot.run.profiles=local`（首页 http://localhost:8080/gp/ ，context-path `/gp`）。`local` profile 豁免 `StartupConfigValidator`，并禁用 AI/通知外部依赖。
- **Flyway 迁移对全新空库不自洽（关键坑）**：仓库里 `db/migration/V1–V20` 是从已删除的 `SchemaInitializer` 回填而来，很多是 `ALTER`（如 `V8` 改 `prosperity_hot_sector`），依赖 SchemaInitializer 时代已建好的基表。`application.yml` 用 `baseline-version: 20` 让生产老库跳过 V1–V20，只跑 V21+。**对全新空 MySQL，Flyway 会在 V8 直接报错启动失败**。因此本地开发用 `application-local.yml` 关掉 Flyway、改用 Hibernate 从 JPA 实体建表（`spring.jpa.hibernate.ddl-auto: update` + `spring.flyway.enabled: false`），这也和 H2 测试 profile 的做法一致。
- **`application-local.yml` 被 .gitignore 忽略**（不会进 PR，但会随 VM 快照保留）。若丢失需重建，最小内容：datasource 指向 `127.0.0.1:3306/wucai_trade`、`username/password` 为 `root/root`、`app.jwt.secret` 给任意非空 dev 值、`spring.jpa.hibernate.ddl-auto: update`、`spring.flyway.enabled: false`、AI 与 notification 全部 `enabled: false`。可从 `application-local.yml.example` 起步，再补上 `ddl-auto: update` + `flyway.enabled: false` 两项（example 本身不含，直接用会触发上面的 Flyway 坑）。
- **首次启动自动建 admin**：`FirstAdminBootstrap` 在库里无用户时创建 `admin`，随机密码打印在启动日志（搜 `首次启动` / `密码`）。API 登录：`POST /gp/api/auth/login`，body `{"username":"admin","password":"..."}`，返回 `accessToken`（Bearer）。注意前端登录弹窗只支持邮箱/手机/登录码，用户名+密码登录仅走该 API；前端页面从 `localStorage` 读 token（layout/header 用 `gp_auth_token`，`journal.js` 用 `token`）。
- **测试无需 MySQL**：`mvn test` 用内存 H2（`application-test.yml`，Flyway 关闭、Hibernate `create-drop`），全部用例通过。
- **Lint 现状（坑）**：`mvn checkstyle:check` 干净通过；但 `mvn spotless:check` 在 `main` 上就对一批既有文件报 google-java-format 格式漂移，所以 `mvn verify`（含 spotless，`verify` 阶段）当前会在 spotless 处失败——这是既有状态，不是环境问题，别为它去改无关代码。需要格式化时才手动 `mvn spotless:apply`。
- 生产脚本 `restart.sh` 走 `prod` profile，会强制校验一堆真实密钥，**不要在 Cloud VM 里用它做本地验证**。
