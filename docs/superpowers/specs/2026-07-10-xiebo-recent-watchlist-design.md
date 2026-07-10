# 谢博投资 — 近期关注 tab 设计文档

> 日期: 2026-07-10
> 状态: 待用户复核
> 项目: gupiao-quant(Spring Boot 3 monolith)

## 1. 目标

在谢博投资 (`/gp/invest.html`) 顶部增加第 4 个一级 tab "近期关注",作为管理员维护的股票池 + 用户个性化订阅的新功能模块。所有用户(登录与否)都能浏览列表;登录用户可以为每只股票设置 5 个价格阈值 + 个人 SCKEY,达到阈值后通过 Server酱推送个性化提醒。管理员在 admin 后台 (`admin-users.html`) CRUD 股票并维护带格式的学习笔记。

## 2. 顶层需求

### 2.1 用户侧(`invest.html` 第 4 tab)

- 顶部显示红色加粗提示:"重要提示:本网站仅用于个人投资学习记录,不构成任何投资建议。股市有风险,投资需谨慎,用户出现任何亏损,概于本网站信息无关。"
- 表格列出所有 `invest_xiebo_recent_watch` 股票,字段:
  - 股票名、类型(科技AI/创新药/质量优选)、当前股价(腾讯行情,初次进入页面拉一次,带手动刷新按钮)
  - 5 个价格输入框:买入 ≤ / 止损 ≤ / 加仓 ≤ / 减仓 ≥ / 清仓 ≥
  - 状态下拉:关注 / 建仓 / 减仓 / 清仓
  - 订阅勾选框
  - 笔记按钮:展开行显示管理员笔记
  - 重置按钮:清空 5 个 triggered_at

### 2.2 管理员侧(`admin-users.html` 新 tab)

- 表格:`股票代码 | 股票名 | 类型 | 新增时间 | 操作(编辑/笔记/删除)`
- 新增 / 编辑股票 modal,包含富文本笔记编辑器(wangEditor)
- 删除股票:确认弹窗,级联删除 `invest_xiebo_stock_note`,`user_stock_subscription` 保留为孤儿

### 2.3 价格提醒

- 5 个独立阈值,每个阈值各自独立触发一次推送,记录 `triggered_at`
- 触发后该提醒永久禁用,直到用户在 UI 上点 "重置" 按钮
- 推送优先级:`subscription.serverchan_send_key` → `user.serverchan_send_key` → 都没有则只写 `invest_alert` 不推送
- 推送周期:交易日(周一至周五)9:00-15:00,每 5 分钟一次

## 3. 架构

```
┌─────────────────────────────────────────────────────────────┐
│                      用户浏览器                                │
│ ┌─────────────────────────┐  ┌──────────────────────────┐    │
│ │ invest.html             │  │ admin-users.html          │    │
│ │ [近期关注 tab]           │  │ [近期关注股票 tab]         │    │
│ │  → js/xiebo-recent.js   │  │  → js/admin-xiebo-recent  │    │
│ │  → wangEditor (admin)   │  │  → wangEditor             │    │
│ └─────────────────────────┘  └──────────────────────────┘    │
└────────────────┬────────────────────────┬───────────────────┘
                 │ REST                   │ REST
                 ▼                        ▼
┌─────────────────────────────────────────────────────────────┐
│                     Spring Boot 后端                           │
│  Controller 层                                              │
│  ├─ XieboRecentController        (/api/xiebo/recent/*)      │
│  ├─ UserXieboRecentController    (/api/me/recent/*)         │
│  ├─ AdminXieboRecentController   (/api/admin/xiebo/recent/*)│
│  └─ NoteImageUploadController    (/api/admin/upload/note-image)│
│  Service 层                                                  │
│  ├─ XieboRecentService                                     │
│  ├─ XieboRecentSubscriptionService                          │
│  └─ XieboRecentAlertJob (@Scheduled cron="0 */5 9-15 * * MON-FRI")│
│  External                                                    │
│  └─ AStockDataQuoteService (腾讯行情)                        │
│  └─ NotificationService (扩展 per-user SCKEY)              │
└────────────────┬────────────────────────────────────────────┘
                 ▼ JPA
┌─────────────────────────────────────────────────────────────┐
│                       MySQL (wucai_trade)                    │
│  invest_xiebo_recent_watch    [新建]                         │
│  invest_xiebo_stock_note      [新建]                         │
│  user_stock_subscription      [新建]                         │
│  auth_user +serverchan_send_key [加列]                      │
│  invest_xiebo_watchlist       [保留]                         │
└─────────────────────────────────────────────────────────────┘
```

## 4. 数据模型

### 4.1 `invest_xiebo_recent_watch`(管理员维护,共享)

```sql
CREATE TABLE invest_xiebo_recent_watch (
    stock_code      VARCHAR(16) PRIMARY KEY,
    stock_name      VARCHAR(64) NOT NULL,
    type            VARCHAR(16) NOT NULL COMMENT '科技AI|创新药|质量优选',
    created_at      DATETIME NOT NULL,
    created_by_admin_id BIGINT NULL,
    updated_at      DATETIME NULL,
    INDEX idx_type (type),
    INDEX idx_created_at (created_at DESC)
);
```

### 4.2 `invest_xiebo_stock_note`(管理员笔记,共享)

```sql
CREATE TABLE invest_xiebo_stock_note (
    stock_code      VARCHAR(16) PRIMARY KEY,
    note_html       LONGTEXT NULL,
    updated_at      DATETIME NULL,
    updated_by_admin_id BIGINT NULL,
    CONSTRAINT fk_note_stock FOREIGN KEY (stock_code)
        REFERENCES invest_xiebo_recent_watch(stock_code) ON DELETE CASCADE
);
```

### 4.3 `user_stock_subscription`(用户个性化)

> 设计:subscription 行一旦创建即永久存在;`enabled` 字段控制"是否触发提醒"。
> 勾选订阅 = `enabled=true`,取消订阅 = `enabled=false`(价格字段不丢)。

```sql
CREATE TABLE user_stock_subscription (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT NOT NULL,
    stock_code      VARCHAR(16) NOT NULL,
    enabled         TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否启用提醒',
    status          VARCHAR(16) NOT NULL DEFAULT '关注' COMMENT '关注|建仓|减仓|清仓',
    status_updated_at DATETIME NULL,
    -- 5 个价格阈值
    price_buy           DECIMAL(10,2) NULL COMMENT '≤ 触发买入提醒',
    price_stop_loss     DECIMAL(10,2) NULL COMMENT '≤ 触发止损提醒',
    price_add_position  DECIMAL(10,2) NULL COMMENT '≤ 触发加仓提醒',
    price_reduce_position DECIMAL(10,2) NULL COMMENT '≥ 触发减仓提醒',
    price_clear_position  DECIMAL(10,2) NULL COMMENT '≥ 触发清仓提醒',
    -- 5 个触发时间戳(去重守护)
    alert_buy_triggered_at           DATETIME NULL,
    alert_stop_loss_triggered_at     DATETIME NULL,
    alert_add_position_triggered_at  DATETIME NULL,
    alert_reduce_position_triggered_at DATETIME NULL,
    alert_clear_position_triggered_at  DATETIME NULL,
    -- SCKEY(可选,fallback 到用户表)
    serverchan_send_key VARCHAR(64) NULL,
    created_at      DATETIME NOT NULL,
    updated_at      DATETIME NOT NULL,
    version         INT NOT NULL DEFAULT 0,            -- 乐观锁,防并发触发
    UNIQUE KEY uk_user_stock (user_id, stock_code),
    INDEX idx_user (user_id),
    INDEX idx_stock (stock_code),
    INDEX idx_enabled (enabled, stock_code)
);
```

### 4.4 `auth_user` 加列

```sql
ALTER TABLE auth_user
  ADD COLUMN serverchan_send_key VARCHAR(64) NULL COMMENT '默认 Server酱 SendKey';
```

### 4.5 关系

- `invest_xiebo_recent_watch` ←(1:1)→ `invest_xiebo_stock_note`(by stock_code,级联删除)
- `invest_xiebo_recent_watch` ←(1:N)→ `user_stock_subscription`(by stock_code,FK 不强制,允许孤儿)
- `auth_user` ←(1:N)→ `user_stock_subscription`(by user_id,FK 不强制,允许用户被删后保留订阅作为审计)

## 5. API 设计

### 5.1 权限矩阵

| 端点 | 公开 | 登录 | 管理员 |
|---|---|---|---|
| `GET /api/xiebo/recent` | ✅ | ✅ | ✅ |
| `GET /api/xiebo/recent/{code}/note` | ✅ | ✅ | ✅ |
| `GET /api/me/recent/subscriptions` | ❌ | ✅ | ✅ |
| `PUT /api/me/recent/subscriptions/{code}` (upsert enabled+status+5价格+SCKEY) | ❌ | ✅ | ✅ |
| `POST /api/me/recent/subscriptions/{code}/reset-alerts` | ❌ | ✅ | ✅ |
| `PUT /api/me/serverchan-key` (用户默认 SCKEY) | ❌ | ✅ | ✅ |
| `POST /api/admin/xiebo/recent` | ❌ | ❌ | ✅ |
| `PUT /api/admin/xiebo/recent/{code}` | ❌ | ❌ | ✅ |
| `DELETE /api/admin/xiebo/recent/{code}` | ❌ | ❌ | ✅ |
| `PUT /api/admin/xiebo/recent/{code}/note` | ❌ | ❌ | ✅ |
| `POST /api/admin/upload/note-image` | ❌ | ❌ | ✅ |

### 5.2 关键接口示例

```http
GET /api/xiebo/recent?type=科技AI
→ 200
{
  "items": [
    { "stockCode": "600519", "stockName": "贵州茅台", "type": "科技AI",
      "currentPrice": 1893.20, "priceChange": 1.32,
      "hasNote": true, "createdAt": "2026-07-08 10:30:00" },
    ...
  ]
}
```

```http
PUT /api/me/recent/subscriptions/600519
{
  "enabled": true,                      // 勾选 = true,取消 = false
  "status": "关注",
  "priceBuy": 1850.00, "priceStopLoss": 1800.00,
  "priceAddPosition": 1820.00,
  "priceReducePosition": 1950.00,
  "priceClearPosition": 2000.00,
  "serverchanSendKey": "SCT..."        // 可选,fallback 到用户表
}
→ 200 { "ok": true, "subscriptionId": 42, "enabled": true }

POST /api/me/recent/subscriptions/600519/reset-alerts
→ 200 { "ok": true }    // 清空 5 个 triggered_at
```

```http
PUT /api/admin/xiebo/recent/600519/note
{ "noteHtml": "<p>...</p>" }
→ 200 { "ok": true, "updatedAt": "..." }

POST /api/admin/upload/note-image (multipart, field: file)
→ 200 { "url": "/uploads/notes/202607/abc123.png" }
```

### 5.3 校验规则

- `enabled`:必填 boolean
- `status` ∈ {关注, 建仓, 减仓, 清仓};只在 `enabled=true` 时必填
- 5 个价格字段:`DECIMAL(10,2)`,可空(空 = 该提醒禁用);> 0
- `serverchanSendKey`:可选,≤ 64 字符
- 上传文件:`image/png|jpeg|gif|webp`,≤ 5MB
- 启用提醒的最终条件 = `enabled=true AND 至少 1 个价格字段非空`

### 5.4 错误响应

```json
{ "ok": false, "errorCode": "PRICE_INVALID", "errorMessage": "买入价必须 > 0" }
{ "ok": false, "errorCode": "UNAUTHENTICATED", "errorMessage": "请先登录" }
{ "ok": false, "errorCode": "NOT_ADMIN", "errorMessage": "需要管理员权限" }
{ "ok": false, "errorCode": "FILE_TOO_LARGE", "errorMessage": "图片不能超过 5MB" }
{ "ok": false, "errorCode": "INVALID_FILE_TYPE", "errorMessage": "只支持 PNG/JPEG/GIF/WEBP" }
```

### 5.5 SecurityConfig 调整

把 `/api/xiebo/recent/**` GET 加入 permitAll 列表。其余不变。

## 6. 提醒引擎

### 6.1 配置

```yaml
xiebo-recent-alert:
  enabled: true
  cron: "0 */5 9-15 * * MON-FRI"   # 每 5 分钟,交易日 9-15 时
```

### 6.2 流程(`XieboRecentAlertJob`)

```
1. @Scheduled 触发
2. 查所有 enabled=1 的 subscription,且至少 1 个价格字段非空
3. 按 stock_code 分组,1 次 fetchQuotes 拿所有当前价
4. 对每条订阅:
   对 5 个价格字段独立判断:
   - price_buy ≤ currentPrice AND alert_buy_triggered_at IS NULL
     → 触发"买入提醒"
   - price_stop_loss ≤ currentPrice AND alert_stop_loss_triggered_at IS NULL
     → 触发"止损提醒"
   - price_add_position ≤ currentPrice AND alert_add_position_triggered_at IS NULL
     → 触发"加仓提醒"
   - price_reduce_position ≥ currentPrice AND alert_reduce_position_triggered_at IS NULL
     → 触发"减仓提醒"
   - price_clear_position ≥ currentPrice AND alert_clear_position_triggered_at IS NULL
     → 触发"清仓提醒"
5. 每条触发:
   a. 写 invest_alert 记录(复用现有表)
   b. 取 SCKEY:subscription.serverchan_send_key → user.serverchan_send_key → 都没有
   c. 有 SCKEY → NotificationService.sendServerChan(title, content, sendKey)
   d. 都无 → log warn,不推送
   e. 成功推送后 → 写 triggered_at(乐观锁)
6. 单条失败不阻断其他
```

### 6.3 SCKEY 推送扩展

`NotificationService` 加新方法:
```java
public void sendServerChan(String title, String content, String sendKey)
```
保留旧 `sendServerChan(title, content)` 走全局 key(向后兼容)。

### 6.4 重置提醒

UI 表格行"重置"按钮 → `POST /api/me/recent/subscriptions/{code}/reset-alerts`
→ 清空 5 个 triggered_at(下次扫描时重新启用)。

## 7. 前端 UI

### 7.1 `invest.html` 顶部红色提示

放在所有 tab 外,作为整个页面的提示:

```html
<div style="background:#fff3cd;color:#c00;font-weight:bold;
            padding:10px 16px;border:1px solid #f5c6cb;border-radius:4px;
            margin-bottom:12px;text-align:center;">
  重要提示:本网站仅用于个人投资学习记录,不构成任何投资建议。
  股市有风险,投资需谨慎,用户出现任何亏损,概于本网站信息无关。
</div>
```

### 7.2 第 4 个 tab

在 `.invest-tabs` 加第 4 个 button,加对应 `.invest-panel[id="invest-panel-recent"]`。

### 7.3 "近期关注" panel 结构

```html
<div id="invest-panel-recent" class="invest-panel" hidden>
  <table id="xiebo-recent-table" class="xiebo-recent-table">
    <thead>
      <tr>
        <th>股票</th><th>类型</th><th>现价</th>
        <th>买入≤</th><th>止损≤</th><th>加仓≤</th><th>减仓≥</th><th>清仓≥</th>
        <th>状态</th><th>订阅</th><th>笔记</th><th>重置</th>
      </tr>
    </thead>
    <tbody><!-- 动态渲染 --></tbody>
  </table>
</div>
```

### 7.4 行交互

- **股票单元格**:点击展开行,显示管理员笔记(`GET /api/xiebo/recent/{code}/note`)
- **现价**:页面加载拉一次 + 每行"🔄"按钮手动刷新
- **5 个价格输入框**:
  - 未登录 → readonly + placeholder "登录后可设置"
  - 登录后首次访问 → 5 个 input 可编辑但 subscription row 还不存在;第一次失焦时自动 `PUT /api/me/recent/subscriptions/{code}` 创建(enabled=false)
  - 已订阅 → 可编辑,失焦后保存
- **状态下拉**:变更即 `PUT` 保存(只更新 status)
- **订阅勾选框**(控制 `enabled` 字段):
  - 未勾选 → subscription.enabled=false(价格字段保留,仅不触发)
  - 勾选 + 未登录 → 弹登录引导 modal
  - 勾选 + 已登录但无 SCKEY(用户表 + 订阅表都没有) → 弹 SCKEY 输入 modal
  - 勾选 + 已有 SCKEY → `PUT` 更新 enabled=true,status='关注'
  - 取消勾选 → `PUT` 更新 enabled=false
- **笔记按钮**:📝 → 展开行内显示笔记(只读 HTML)
- **重置按钮**:🔄 → `POST /api/me/recent/subscriptions/{code}/reset-alerts`

### 7.5 `admin-users.html` 新 tab

- 标题:"近期关注股票"
- 表格:`股票代码 | 股票名 | 类型 | 新增时间 | 操作(编辑/笔记/删除)`
- `[+ 新增股票]` 按钮 → modal:股票代码/股票名/类型下拉
- 编辑 modal:同上 + 同步编辑笔记(内嵌 wangEditor)
- 删除:确认弹窗 → DELETE /api/admin/xiebo/recent/{code}
- 编辑笔记:独立 modal,大号 wangEditor + 上传图片按钮

### 7.6 静态资源配置

`application.yml`:
```yaml
spring:
  web:
    resources:
      static-locations: classpath:/static/, file:uploads/
```

这样 `uploads/notes/202607/abc.png` 通过 `/uploads/notes/202607/abc.png` 访问。

### 7.7 上传图片

- 接收:multipart `file` 字段
- 校验:`Content-Type` 必须 `image/png|jpeg|gif|webp`,≤ 5MB
- 存储路径:`uploads/notes/yyyyMM/{uuid}.{ext}`
- 返回:`{ "url": "/uploads/notes/202607/{uuid}.png" }`
- wangEditor 配置:`uploadImgServer: '/api/admin/upload/note-image'`

### 7.8 XSS 防护

- wangEditor 默认开启 XSS filter
- 后端 sanitize HTML(JSoup `Safelist.basicWithImages()`)

## 8. 提醒引擎并发与一致性

- `user_stock_subscription` 加 `version` 列 + JPA `@Version` 注解实现乐观锁
- 同一订阅被 cron 多次同时触发时,只有第一个 `update set triggered_at=now()` 成功;其他失败重试一次
- 行情拉取按 stock_code 批量(`fetchQuotes(List<String>)`),减少 HTTP 调用

## 9. 错误处理

| 场景 | 处理 |
|---|---|
| 行情接口超时 | 单只股票拉价失败 → log warn + 跳过该只,继续其他 |
| SCKEY 失效(推送 4xx) | log error,不更新 triggered_at(下次还能再试) |
| SCKEY 推送成功 | 写 triggered_at + invest_alert |
| 用户提交价格 ≤ 0 | 后端 400 `PRICE_INVALID` |
| 股票代码不存在(admin 新增) | 后端校验 stock_code 非空且 ≤ 16 字符 |
| 用户取消订阅 | DELETE subscription,但保留 5 个价格字段(下次再订阅时不丢) |
| 用户重置提醒 | DELETE 5 个 triggered_at,不删价格字段 |
| Admin 删除股票 | DB ON DELETE CASCADE 删除 stock_note;subscription 保留为孤儿(列表查不到) |
| 上传超 5MB | 后端 400 `FILE_TOO_LARGE` |
| 上传非图片类型 | 后端 400 `INVALID_FILE_TYPE` |
| 富文本 XSS | wangEditor 默认 filter + 后端 JSoup sanitize |
| 并发触达同一价格阈值 | `version` 字段乐观锁(JPA `@Version`),失败重试一次 |
| 行情数据缺失(停牌) | currentPrice 为 null,行展示"—",价格判断跳过 |
| 未登录访问 admin 端点 | 403 `NOT_ADMIN` |
| 未登录访问 user 端点 | 401 `UNAUTHENTICATED` |

## 10. 测试策略

目标 80%+ 覆盖率。

| 测试类 | 范围 | 工具 |
|---|---|---|
| `XieboRecentServiceTest` | 拉列表、过滤 type、拉笔记、保存笔记 | JUnit 5 + Mockito |
| `XieboRecentSubscriptionServiceTest` | CRUD、5 价格校验、SCKEY fallback | JUnit 5 + Mockito |
| `XieboRecentAlertJobTest` | 5 价格触发、SCKEY 优先级、triggered_at 守护、去重 | JUnit 5 + Mockito |
| `XieboRecentControllerTest` | `/api/xiebo/recent` GET 公开 | @WebMvcTest |
| `UserXieboRecentControllerTest` | 登录/未登录分支、reset-alerts | @WebMvcTest + @WithMockUser |
| `AdminXieboRecentControllerTest` | ADMIN 权限矩阵、CRUD、note PUT | @WebMvcTest + @WithMockUser(roles="ADMIN") |
| `NoteImageUploadControllerTest` | 类型/大小校验、UUID 命名 | @WebMvcTest + MockMultipartFile |
| `NotificationServiceSendKeyTest` | per-user SCKEY vs 默认 SCKEY | Mockito |
| `SchemaInitializerIT` | 3 张新表 + 1 列 ALTER 自动建出来 | @SpringBootTest |

**关键用例**:
- 5 价格各自独立触发(买入 ≤ X 触发,止损 ≤ X 也触发)
- 触发后不再重复(`triggered_at` 守护)
- SCKEY 三层优先级(订阅 > 用户 > 都不推送)
- 股票被 admin 删除后,user_stock_subscription 保留(stock_code 变孤儿)
- 上传非图片文件返回 400
- 未登录访问 admin 端点返回 403
- 公开 GET `/api/xiebo/recent` 不需要 token

## 11. 文件清单

### 11.1 新建(后端 Java)

- `entity/InvestXieboRecentWatch.java`
- `entity/InvestXieboStockNote.java`
- `entity/UserStockSubscription.java`
- `repository/InvestXieboRecentWatchRepository.java`
- `repository/InvestXieboStockNoteRepository.java`
- `repository/UserStockSubscriptionRepository.java`
- `controller/XieboRecentController.java`
- `controller/UserXieboRecentController.java`
- `controller/AdminXieboRecentController.java`
- `controller/NoteImageUploadController.java`
- `service/XieboRecentService.java`
- `service/XieboRecentSubscriptionService.java`
- `service/XieboRecentAlertJob.java`
- `dto/xiebo/RecentWatchDto.java`
- `dto/xiebo/RecentNoteDto.java`
- `dto/xiebo/UserSubscriptionDto.java`
- `dto/xiebo/UserSubscriptionUpsertRequest.java`
- `dto/xiebo/AdminRecentStockRequest.java`
- `dto/xiebo/AdminNoteUpdateRequest.java`

### 11.2 新建(前端)

- `static/js/xiebo-recent.js`(新 tab 交互逻辑)
- `static/js/admin-xiebo-recent.js`(admin CRUD)
- `static/lib/wangEditor.min.js` (前端依赖,见 11.5)

### 11.3 新建(SQL)

- `sql/xiebo_recent_init.sql`(3 张新表 CREATE TABLE)
- `sql/auth_user_alter_serverchan_key.sql`(ALTER TABLE 加列)

### 11.4 修改(后端)

- `config/SchemaInitializer.java` → 加 `ensureXieboRecentTables()` + `ensureAuthUserServerchanKeyColumn()`
- `config/SecurityConfig.java` → 加 `/api/xiebo/recent/**` GET 到 permitAll
- `entity/AuthUser.java` → 加 `serverchanSendKey` 字段
- `service/NotificationService.java` → 加 `sendServerChan(title, content, sendKey)` 方法
- `application.yml` → 加 cron + static-locations 配置

### 11.5 修改(前端)

- `static/invest.html` → 加红色提示 + 第 4 个 tab + panel + 引入 wangEditor JS
- `static/admin-users.html` → 加新 tab + modal + 引入 wangEditor JS

> wangEditor 通过 CDN 引入(`https://unpkg.com/@wangeditor/editor@latest/dist/index.js`)避免本地 vendoring。

### 11.6 新建(测试)

- `test/.../service/XieboRecentServiceTest.java`
- `test/.../service/XieboRecentSubscriptionServiceTest.java`
- `test/.../service/XieboRecentAlertJobTest.java`
- `test/.../controller/XieboRecentControllerTest.java`
- `test/.../controller/UserXieboRecentControllerTest.java`
- `test/.../controller/AdminXieboRecentControllerTest.java`
- `test/.../controller/NoteImageUploadControllerTest.java`
- `test/.../service/NotificationServiceSendKeyTest.java`
- `test/.../integration/SchemaInitializerIT.java`

## 12. 不做(YAGNI)

- ❌ WebSocket/SSE 实时推送(5 分钟 cron 够用)
- ❌ 价格区间提醒(只支持单阈值)
- ❌ 邮件/短信提醒(只支持 Server酱)
- ❌ 移动端 App
- ❌ 多语言(中文 only)
- ❌ 笔记版本历史
- ❌ 价格公式/SOP 检查
- ❌ 把现有 `invest_xiebo_watchlist` 迁移/合并(保留双轨)

## 13. 未来扩展(本次不做)

- 移动端适配(响应式)
- 笔记评论/点赞
- 多 SCKEY 同时推送(钉钉/飞书/邮件)
- 价格历史曲线
- 投资组合收益统计
- AI 自动给每只股票写学习笔记