# 交易日志(Trade Journal)— 设计稿

- **作者**:Claude
- **日期**:2026-06-30
- **状态**:Draft — 用户已批准设计,等待 spec 评审

## 1. 背景与目标

用户当前通过 `position-management.html` 接触"三大数学公式"(R:R、EV、Position Sizing),
但页面只提供**一次性**计算,无历史记录、无复盘统计。
用户需要一套"学习 → 实践 → 复盘"的闭环工具,让自己能按方法论多次迭代,
并用真实数据验证自己交易系统的有效性。

### 核心目标
1. **持久化**每次交易的开仓前思考、持仓、平仓、复盘笔记
2. **自动计算** R-multiple、期望值(EV)、最大回撤
3. **可视化** 累计 R 权益曲线 + R 分布
4. **支持模拟盘** 让用户在不动用真金白银的情况下,演练方法论
5. **与三池(invest_position_fill)集成** 平仓后一键同步,减少重复录入

### 非目标(YAGNI)
- 不做订单执行/下单(只记录已成交或模拟成交)
- 不做实时 tick 级行情展示(沿用现有 `/api/xiebo-invest/quote` 分钟级刷新)
- 不做用户分享/社区对比
- 不做策略回测引擎(这是统计,不是回测)
- 不做移动端原生 app(响应式 web 即可)

## 2. 用户决策摘要

| 决策点 | 选择 |
|---|---|
| 与现有 position-management.html 关系 | **新建** journal.html,不复用/不替换 |
| 交易类型 | **实盘 + 模拟** 都支持 |
| 多周期/阶段 | **不需要**(靠 tags + mode 切片) |
| 集成深度 | **从三池成交拉**(提供同步入口) |
| 可视化 | **要图表**(权益曲线 + R 分布) |
| 交易分类 | **自由标签**(逗号分隔,无预置) |

## 3. 数据模型

### 主表 `journal_trade`

```sql
CREATE TABLE journal_trade (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  mode VARCHAR(10) NOT NULL,             -- 'REAL' / 'PAPER'
  stock_code VARCHAR(20) NOT NULL,
  stock_name VARCHAR(50),

  -- 入场
  entry_price DECIMAL(10,2) NOT NULL,
  entry_date DATETIME NOT NULL,
  entry_shares INT NOT NULL,             -- 实际股数,向下取整到 100
  account_at_entry DECIMAL(14,2),
  risk_percent DECIMAL(5,4),             -- 0.0100 = 1%
  stop_price DECIMAL(10,2) NOT NULL,
  target_price DECIMAL(10,2),            -- 可空

  -- 平仓(可空 = 持仓中)
  exit_price DECIMAL(10,2),
  exit_date DATETIME,
  exit_reason VARCHAR(30),               -- 'stopped_out' / 'target_hit' / 'manual' / 'time_stop' / 'system_stop'
  initial_risk DECIMAL(10,2) NOT NULL,   -- = entry_price - stop_price,冗余存储

  -- 自动计算
  pnl_amount DECIMAL(14,2),
  r_multiple DECIMAL(8,4),               -- (exit-entry)/initial_risk;持仓中为 0
  is_open TINYINT DEFAULT 1,             -- 1=持仓中, 0=已平

  -- 笔记
  tags VARCHAR(200),                     -- 自由标签,逗号分隔
  setup_notes TEXT,
  review_notes TEXT,

  -- 来源
  source VARCHAR(20),                    -- 'MANUAL' / 'POOL_SYNC'
  source_ref_id BIGINT,                  -- 引用 invest_position_fill.id

  is_deleted TINYINT DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  INDEX idx_mode_open (mode, is_open),
  INDEX idx_stock (stock_code),
  INDEX idx_exit_date (exit_date),
  UNIQUE KEY uk_source_ref (source, source_ref_id)  -- 防止 POOL_SYNC 重复
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**为什么只有 1 张表**:入场 + 平仓 + 计算字段都在主表,持仓中 `exit_*` 为 null 即可。
后续真要拆(多笔记/图片),再升级到主子表。

### 唯一索引说明
`UNIQUE KEY uk_source_ref (source, source_ref_id)` 保证从 `invest_position_fill` 同步不会重复入库。
`source_ref_id` 在 `source = 'MANUAL'` 时为 NULL,允许多条记录 NULL。

## 4. 后端架构

### 包结构
```
com.quant
├── controller/JournalController.java
├── service/journal/JournalService.java
├── service/journal/JournalStatsService.java
├── service/journal/JournalCronService.java
├── entity/JournalTrade.java
├── repository/JournalTradeRepository.java
├── dto/journal/
│   ├── JournalTradeDTO.java
│   ├── JournalTradeCreateRequest.java
│   ├── JournalTradeUpdateRequest.java
│   ├── JournalStatsDTO.java
│   ├── EquityCurvePoint.java
│   └── RDistributionBucket.java
```

### REST 接口

| Method | Path | 用途 |
|---|---|---|
| POST | `/api/journal/trades` | 新建(实盘/模拟) |
| PUT | `/api/journal/trades/{id}` | 更新(平仓、改笔记、调止损) |
| GET | `/api/journal/trades` | 列表(filter: `mode`, `isOpen`, `tag`, `from`, `to`) |
| GET | `/api/journal/trades/{id}` | 详情 |
| DELETE | `/api/journal/trades/{id}` | 软删除(`is_deleted = 1`) |
| GET | `/api/journal/stats` | 统计聚合(支持 mode 过滤) |
| GET | `/api/journal/equity-curve` | 累计 R 曲线数据点 |
| GET | `/api/journal/r-distribution` | R 分桶 |
| GET | `/api/journal/pending-fills` | 待同步的 invest_position_fill 列表 |
| POST | `/api/journal/sync-from-fill/{fillId}` | 从 fill 创建 journal_trade |

所有 `/api/journal/*` 需要 JWT 认证(`.authenticated()`),不复用 `permitAll` 列表。

### 关键算法

**R Multiple**(单笔):
```
initial_risk = entry_price - stop_price    -- 每股风险
pnl = (exit_price - entry_price) * entry_shares
r_multiple = pnl / (initial_risk * entry_shares)
```

**期望值 EV**(基于已平仓样本):
```
win_rate = wins / total_closed
loss_rate = 1 - win_rate
avg_win_r = sum(r_multiple where r>0) / wins
avg_loss_r = sum(r_multiple where r<0) / losses   -- 负值
EV = win_rate * avg_win_r + loss_rate * avg_loss_r  -- 注意 loss_rate * avg_loss_r < 0
```

**最大回撤**(按平仓时间排序的累计 R):
```
equity[i] = sum(r_multiple[0..i])
peak[i] = max(equity[0..i])
drawdown[i] = equity[i] - peak[i]      -- 始终 ≤ 0
max_drawdown = min(drawdown)            -- 最深回撤
```

**权益曲线**(给前端画图):
```
按 exit_date ASC 排序
points = [{ tradeIndex: 1, cumulativeR: 0.5 }, ...]
```

**R 分布桶**:
```
buckets = [
  { label: '<-2R', range: [-∞, -2), count: ... },
  { label: '-2~-1R', range: [-2, -1), count: ... },
  { label: '-1~0R',  range: [-1, 0),  count: ... },
  { label: '0~1R',   range: [0, 1),   count: ... },
  { label: '1~2R',   range: [1, 2),   count: ... },
  { label: '2~3R',   range: [2, 3),   count: ... },
  { label: '>3R',    range: [3, +∞), count: ... }
]
```

### Cron

**每日盘后 15:30** 跑一遍(`JournalCronService.refreshOpenTrades()`):
1. 找出 `is_open = 1` 的所有 trade
2. 对每条用 `/api/xiebo-invest/quote` 拉当前价,更新浮盈 R 倍数(暂不持久化到 `r_multiple` 字段,只用于"进行中"卡片显示)
3. 若当前价 ≥ `target_price`(做多触达),自动 `is_open = 0` + `exit_price = target_price` + `exit_reason = 'target_hit'` + Server酱 推送

**新增 cron 表达式**:`application.yml` 加 `journal.refresh-cron: "0 30 15 * * MON-FRI"`(Spring `@Scheduled` 5 字段格式)

### SchemaInitializer 集成

按 CLAUDE.md 现有约定,改两处:
1. `config/SchemaInitializer.java` 加 `ensureJournalTables()` 方法
2. 新建 `sql/journal_init.sql`(供新部署一次性执行)

## 5. 前端架构

### 入口
- 新建 `/gp/journal.html`
- header.html 加导航:`复盘` → `/gp/journal.html`

### 页面布局(三栏)

```
┌──────────────────────────────────────────────────────────────┐
│  Header                                                      │
├────────────┬────────────────────────────────┬────────────────┤
│ 左:新建    │ 中:交易列表                     │ 右:统计         │
│ (300px)    │ (自适应)                        │ (360px)         │
├────────────┼────────────────────────────────┼────────────────┤
│ [实盘/模拟]│ Tab:[进行中][已平仓][全部]       │ Mode: [ALL▾]   │
│ 股票代码   │ ┌────────────────────────────┐ │ ┌────────────┐ │
│ 自动拉价   │ │ 600519 茅台  [REAL]         │ │ │胜率 42%    │ │
│ 入场价     │ │ 入 1680 / 损 1620 / 1850    │ │ │平均R +0.8  │ │
│ 止损价     │ │ 持仓 5 手 | 浮盈 R: +0.5    │ │ │EV +120     │ │
│ 目标价     │ │ 标签:海龟突破,练习1         │ │ │回撤 -8R    │ │
│ 股数(算)   │ └────────────────────────────┘ │ └────────────┘ │
│ Setup笔记  │ ┌────────────────────────────┐ │                │
│            │ │ 002415 海康 [PAPER]          │ │ 权益曲线       │
│ 提交前     │ │ 已平 +2.3R ✓ 目标达成        │ │ [Chart.js]    │
│ ☑ R:R≥1:3 │ └────────────────────────────┘ │                │
│ ☑ 风险≤2% │ ┌────────────────────────────┐ │ R 分布          │
│ ☑ 止损已设 │ │ ...                         │ │ [Chart.js]    │
│ ☑ 未加仓   │ └────────────────────────────┘ │                │
│ ☑ 计划交易 │                                │                │
│ [保存]     │                                │                │
└────────────┴────────────────────────────────┴────────────────┘
```

### 关键交互

**左栏 · 新建表单**:
- 输入股票代码 → debounce 300ms → `GET /api/xiebo-invest/quote?keyword=` → 拉当前价
- 入场价 / 止损价 / 目标价 实时联动算 R:R(< 1:3 红字警告)
- 5 条红线 checklist:每条 checkbox 必须全勾才能点"保存"
- 保存后清空表单,中栏列表自动刷新

**中栏 · 交易列表**:
- 默认按 `entry_date DESC`
- 点击展开 → 平仓表单(exit_price + exit_reason + review_notes)
- 进行中卡片显示当前浮盈 R 倍数(每 30s 拉一次 quote)
- POOL_SYNC 来源 → 右上角小徽章"同步自三池",hover 显示原 fill id

**右栏 · 统计**:
- 顶部 Mode 下拉:`ALL / REAL / PAPER`
- 4 张统计卡:胜率 / 平均 R / EV / 最大回撤(单位:R)
- 权益曲线:Chart.js line,X=交易序号,Y=累计 R
- R 分布:Chart.js bar,X=桶,Y=数量

### 复用现有
- CSS:沿用 `css/position-management.css` 的卡片/表格样式
- 拉价:复用 `fetchCurrentPrice()` 函数(从 `position-management.js` 第 289 行挪过来)
- Chart.js:CDN 已有,直接 `<script src="https://cdn.jsdelivr.net/npm/chart.js@4"></script>`

## 6. 测试

### 单测(目标覆盖率 ≥ 80%)

| 测试类 | 关键用例 |
|---|---|
| `JournalStatsServiceTest` | 0 笔返回空统计;全胜样本 EV 正确;全负样本 EV 正确;混合样本胜率/EV/回撤正确;R 分桶边界 |
| `JournalServiceTest` | 创建时 `initial_risk` 自动算;平仓时 R 倍数/PnL 自动算;`is_open` 切换;R:R < 1:3 拒绝;risk_percent > 2% 拒绝 |
| `JournalControllerTest` | 10 个 endpoint 正常路径;filter 参数;权限检查;POOL_SYNC 重复返回 409 |
| `JournalCronServiceTest` | 浮盈 R 实时刷新;target_price 触达自动平仓 + Server酱 |

### 手工冒烟
1. 新建实盘单 → R 倍数/PnL 正确
2. 新建模拟单 → 标记 PAPER 徽章
3. 平仓 → 统计面板数字更新
4. 从 pending-fills 同步一条 → 检查 `source_ref_id` 正确,重复同步返回 409
5. cron 15:30 触发 → 持仓中 trade 浮盈 R 实时刷新,目标触达自动平仓 + 推送
6. R:R < 1:3 → 提交按钮禁用 + 红色提示
7. 5 条红线少勾 → 提交按钮禁用 + 提示"违反纪律红线"

## 7. 风险与边界

| 风险 | 处置 |
|---|---|
| `invest_position_fill` 重复同步 | `UNIQUE KEY uk_source_ref` + 重复请求返回 409 |
| 删 trade 影响 R 曲线 | 软删除 `is_deleted = 1`,统计过滤掉 |
| 多端编辑冲突 | 简化处理:最后写赢(updated_at 检查) |
| 跨周期统计 | 靠 tags 自由切片(`?tag=练习1`),不强制周期 |
| 模拟盘数据污染实盘统计 | mode 字段隔离,UI 默认 ALL,统计可按 mode 过滤 |
| target_price 自动平仓误触 | 仅 cron 触发,人工确认前 R:R 仍按当前价显示;自动平仓后 review_notes 标记"system_stop" |
| 5 条红线可被绕过 | 前端 disabled + 后端硬校验两道防线:①R:R < 1:3 直接 400;②`risk_percent > 0.02` 直接 400;其余 3 条(止损已设/未加仓/计划交易)是主观纪律,前端 warning + 后端不阻断,记录到 `setup_notes` 由用户自证 |

### 5 条红线双轨校验清单

| 红线 | 前端 | 后端 | 说明 |
|---|---|---|---|
| R:R ≥ 1:3 | checkbox + 红字警告 | **硬校验**(400) | 数学硬约束 |
| 风险 ≤ 2% | checkbox + 红字警告 | **硬校验**(400) | 数学硬约束 |
| 止损已设 | checkbox | 不阻断 | 主观纪律 |
| 未加仓 | checkbox | 不阻断 | 主观纪律 |
| 计划交易 | checkbox | 不阻断 | 主观纪律 |

## 8. 里程碑

| M | 内容 | 预估 |
|---|---|---|
| M1 | Schema + Entity + Repository + 10 个 endpoint | 0.5d |
| M2 | JournalService 业务逻辑 + R/PnL 自动计算 | 0.5d |
| M3 | JournalStatsService(EV/回撤/分布) | 0.5d |
| M4 | JournalCronService + POOL_SYNC 同步 | 0.5d |
| M5 | 前端三栏 + 新建表单 + 5 条红线 checklist | 1.0d |
| M6 | 列表 + 平仓表单 + 统计卡 + Chart.js 图表 | 1.0d |
| M7 | 单测 ≥ 80% + 手工冒烟 + restart.sh 部署 | 0.5d |

## 9. 与现有能力的关系

| 现有 | 复用方式 |
|---|---|
| `/api/xiebo-invest/quote?keyword=` | 拉当前价 |
| `invest_position_fill` 表 | POOL_SYNC 来源 |
| `NotificationProperties`(Server酱) | 目标触达推送 |
| `position-management.js` 的 `fetchCurrentPrice` | 抽出公用函数 |
| `css/position-management.css` 卡片样式 | 复用 |
| `header.html` 导航 | 加"复盘"入口 |
| `SchemaInitializer` 模式 | `ensureJournalTables()` 跟进 |

## 10. Open Questions(已闭合)

无遗留问题。所有关键决策已在第 2 节列出。
