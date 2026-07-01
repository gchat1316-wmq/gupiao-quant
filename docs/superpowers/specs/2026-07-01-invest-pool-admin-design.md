# 股票池管理迁至 admin 后台 — 设计稿

- **作者**: Mavis (Claude)
- **日期**: 2026-07-01
- **状态**: Draft — 用户已批准设计,等待 spec 评审

## 1. 背景与目标

`invest.html` 股票池 panel 当前对所有登录用户暴露「+ 加入股票池」「📷 截图批量导入」和行内「编辑 / 删除 / 备注」按钮,这些是**管理动作**,与前台只读浏览的定位冲突,普通用户看到会困惑,管理员反而要在一堆只读 UI 里找操作入口。

`admin-users.html` 已经把「股票池元信息(封面图 / 估值方法 / 9 格推荐)」迁过去过(`poolmeta` panel),但**股票条目本身的 CRUD 还没迁**,后端 API 其实早就齐了,只是前端 UI 没接管,导致能力「后端有 / 前端无」,管理员只能继续在 `invest.html` 上操作。

### 核心目标

1. **把股票池 CRUD 全部迁到 `admin-users.html` 新 panel**: 列表、新增、编辑、删除、备注、OCR 批量导入
2. **`invest.html` 干净化**: 删掉所有写操作入口,只保留只读浏览 + 搜索 + 筛选
3. **新增 reorder API**: 拖拽排序需要批量更新 `displayOrder`,现有 API 只支持单条 PATCH
4. **保持性能**: 复用刚加的 30s stockPool cache + @CacheEvict,管理操作后立即失效

### 非目标(YAGNI)

- 不重做 invest.html 表格样式(只删按钮,不重排版)
- 不做批量删除(逐只 confirm 即可,池子规模 30~50 只)
- 不做导入导出 Excel(OCR 截图入库已够用)
- 不做审计日志(后续有需要再补)
- 不做权限细分(沿用现有 `@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")`)

## 2. 用户决策摘要

| 决策点 | 选择 |
|---|---|
| admin panel 放哪里 | **`admin-users.html` 加新 panel**(单一后台、复用权限/布局) |
| `invest.html` 管理入口处理 | **全部删除**(前台彻底只读,Admin 从 header「⚙️ 管理后台」进) |
| 排序交互 | **HTML5 拖拽**(整行拖动,松手一次提交 reorder API) |
| displayOrder 重排策略 | **系统自动重排为 10/20/30…**(避免出现 5/17/89 跳跃值) |
| OCR 流程 | **保留两步**(上传 → 预览识别结果 → 逐条勾选 → 批量入库) |
| 表格筛选 | **加搜索框**(按代码 / 名称 / 状态) |

## 3. 数据模型

无需新增表 / 改 schema。复用现有 `invest_stock_pool` 表。

### 现有字段(回顾)

- 主键 `id`(自增)
- `stock_code` + `stock_name` — 股票标识
- `pool_type` — `tech_vc` / `innovative_drug` / `quality`
- `display_order` — int,同池内排序,越小越靠前
- 估值/预测/财务字段:`undervalued_price` / `fair_price` / `overvalued_price` / `target_*_price` / `revenue_forecast_y*` / `revenue_2023~2025` / `q1_*` / `min_ps5y` / `target_market_cap`
- `memo` — text,备注
- `created_at` / `updated_at`

### 不动 schema 的原因

- 所有 CRUD 后端 API 已有,只是前端 UI 没接管
- displayOrder 已有 int 字段,无需新增

## 4. API 改动

### 4.1 复用现有 API(无改动)

| 操作 | Method + Path | 权限 |
|---|---|---|
| 列表 | `GET /api/invest/pool?poolType={X}` | permitAll(已在 SecurityConfig) |
| 新增 | `POST /api/invest/pool` | MANAGER + ADMIN |
| 全字段编辑 | `PUT /api/invest/pool/{id}` | MANAGER + ADMIN |
| 单字段编辑 | `PATCH /api/invest/pool/{id}/field` | MANAGER + ADMIN |
| 删除 | `DELETE /api/invest/pool/{id}` | MANAGER + ADMIN |
| OCR 解析(不入库) | `POST /api/invest/pool/import-image` | MANAGER + ADMIN |
| OCR 批量入库 | `POST /api/invest/pool/batch-import` | MANAGER + ADMIN |

### 4.2 新增 1 个 API

#### `POST /api/invest/pool/reorder`

**权限**: MANAGER + ADMIN

**Request Body**:
```json
[
  {"id": 12, "displayOrder": 10},
  {"id": 7,  "displayOrder": 20},
  {"id": 23, "displayOrder": 30}
]
```

**Response**:
```json
{
  "updated": 3,
  "message": "reordered"
}
```

**校验**:
- Body 必须是非空数组
- 每项必须有 `id`(正整数,必须存在于 `invest_stock_pool`)
- 每项必须有 `displayOrder`(非负整数)
- `displayOrder` 允许重复(后续会用 10/20/30 等距步长重排)

**实现要点**:
- `InvestStockPoolRepository` 新增 `@Modifying @Query("UPDATE InvestStockPool p SET p.displayOrder = :order WHERE p.id = :id")` 批量更新
- `InvestService.reorder(List<IdOrderPair>)` — 遍历执行上述 query,**事务** + **`@CacheEvict("stockPool", allEntries=true)`**
- 性能:30~50 条 UPDATE 单事务内串行执行,<< 1s

**测试**:
- Controller auth test:`POST /reorder` 不带 token → 401;非 MANAGER/ADMIN → 403
- Service unit test:mock repository,验证 query 被调用 N 次且参数正确

## 5. 前端改动

### 5.1 admin-users.html — 新 panel: `pool-crud`

#### 位置

侧边栏第 5 项「股票池」,在 `poolmeta` 之后、`quotes` 之前。

```
[侧边栏]
├─ 📊 数据概览
├─ 👥 用户管理
├─ 🎯 股票池元信息      ← 现有
├─ 📋 股票池管理         ← 新增
└─ 💬 行情中心
```

#### Panel DOM 结构(摘要)

```html
<div id="panel-poolcrud" class="panel hidden">
  <div class="page-title">股票池管理</div>
  <div class="page-subtitle">统一管理 3 个股票池的条目:增删改、排序、OCR 批量导入</div>

  <div class="poolcrud-tabs">
    <button data-pool="tech_vc" class="active">科技AI (22)</button>
    <button data-pool="innovative_drug">创新药 (7)</button>
    <button data-pool="quality">质量优选 (0)</button>
  </div>

  <div class="poolcrud-toolbar">
    <button id="poolcrudAddBtn">+ 新增股票</button>
    <button id="poolcrudImportBtn">📷 截图批量导入</button>
    <button id="poolcrudRefreshBtn">🔄 刷新</button>
    <input id="poolcrudSearch" placeholder="搜索代码/名称/状态" />
    <span id="poolcrudCount">共 22 只</span>
  </div>

  <table id="poolcrudTable" class="poolcrud-table">
    <thead><tr>
      <th>≡</th><th>#</th><th>代码</th><th>名称</th>
      <th>低估/合理/高估</th><th>目标价</th>
      <th>备注</th><th>状态</th><th>操作</th>
    </tr></thead>
    <tbody id="poolcrudTbody"></tbody>
  </table>
</div>

<!-- 新增/编辑 modal -->
<div id="poolcrudEditModal" class="modal hidden">...</div>

<!-- OCR 批量导入 modal -->
<div id="poolcrudOcrModal" class="modal hidden">...</div>
```

#### 拖拽交互

- 整行 `<tr draggable="true">`,鼠标按下非按钮区域 → 可拖
- 拖动时:`tr.dragging` 半透明 + 显示 shadow + 其他行显示 drop indicator(顶部或底部 2px 蓝线)
- `dragover` 阻止默认 + 计算插入位置
- `drop` 后:本地更新 `tbody` 顺序 + 计算新的 displayOrder(等距步长,如 10/20/30…)+ 1 次 POST `/reorder`
- 失败回滚:API 报错 → 恢复原顺序 + toast 报错

#### 新增/编辑 modal 字段

沿用现有 `investModalMask` 的字段集(已存在 `invest.js` 里的 `modalPoolType` / `modalKeyword` / `modalDisplayOrder` 等),迁移到 admin 命名空间。完整字段:

- 股票代码 / 名称(输入框,后端 `resolveStock` 自动匹配)
- 分类(select:tech_vc / innovative_drug / quality)
- 排序(数字,默认 100)
- memo(textarea,多行)
- undervalued_price / fair_price / overvalued_price(三个数字)
- target_buy_price / target_sell_price / target_price(三个数字)
- revenue_forecast_y0/y1/y2(三个数字)
- revenue_2023/2024/2025(三个数字)
- q1_gross_margin / q1_net_margin / q1_revenue_growth(三个数字)
- min_ps5y / target_market_cap(两个数字)

#### OCR 批量导入 modal

沿用现有 `importPoolModal` 流程:
1. 上传图片(drag-drop + 文件选择)
2. POST `/api/invest/pool/import-image` → 返回识别结果数组
3. 显示可编辑表格(代码 / 名称 / 分类 / 置信度 / 勾选框)
4. 用户逐条勾选 / 编辑 / 取消勾选
5. 提交 → POST `/api/invest/pool/batch-import`

#### JS 结构

新增独立文件 `admin-poolcrud.js`(避免 admin-users.html 越来越胖):
- 模块 IIFE,内部 state:`currentPool` / `poolData[]` / `searchKeyword`
- 函数:`loadPool` / `renderTable` / `openAddModal` / `openEditModal` / `saveModal` / `confirmDelete` / `startMemoEdit` / `saveMemoEdit` / `initOcrModal` / `submitBatchImport` / `bindDragDrop` / `reorder`

### 5.2 invest.html — 删除管理入口

#### HTML 删除清单

| 位置 | 内容 | 原因 |
|---|---|---|
| `line 267` | `<button id="importPoolBtn">📷 截图批量导入</button>` | OCR 迁 admin |
| `line 268` | `<button id="addPoolBtn">+ 加入股票池</button>` | 新增迁 admin |
| `line 245` | `✎ 管理 9 格` 按钮 | 已迁 poolmeta,本就该隐藏 |
| `line 432-518` | `<div id="investModalMask">` 整个 modal | 移到 admin 命名空间 |
| `line 521+` | `<div id="importPoolModal">` 整个 OCR modal | 同上 |
| 表格内行内按钮 | 编辑 / 删除 / 备注按钮 | 移到 admin |

#### JS 删除清单(`js/invest.js`)

| 函数名 | 行数(估) | 原因 |
|---|---|---|
| `openAddPoolModal` | ~30 | 迁 admin |
| `openEditPoolModal` | ~30 | 迁 admin |
| `closeModal` | ~10 | 跟着 modal 一起删 |
| `savePool` | ~50 | 迁 admin |
| `confirmDelete` / `deletePool` | ~30 | 迁 admin |
| `startMemoEdit` / `saveMemoEdit` | ~40 | 迁 admin |
| `initImportPool` + OCR 相关 | ~150 | 迁 admin |
| 对应 `addEventListener` 全部清理 | ~30 | — |
| 表格 row 事件委托里的"操作按钮"分支 | ~20 | — |

#### 保留(纯展示)

- `loadPool` / `renderPool` 列表展示
- 搜索框 / 筛选按钮
- 元信息(meta)展示
- 每周机会点(weeklyOpp)展示
- 估值三档 / 目标价 / memo cell 显示

## 6. 数据流(典型操作)

### 6.1 新增股票

```
admin pool-crud panel → 点「+ 新增股票」
  → modal 打开,填字段 → 点「保存」
  → POST /api/invest/pool
    → InvestController.addToPool → InvestService.addToPool
      → resolveStock + save + @CacheEvict("stockPool", allEntries=true)
  → 200 OK
  → modal 关闭 + 表格 reload(GET /api/invest/pool?poolType=X)
  → toast「已新增 XXX」
```

### 6.2 拖拽排序

```
admin pool-crud panel → 拖动行 N 从位置 i → 位置 j
  → drop 事件触发
  → 本地 state 更新顺序
  → 重新计算 displayOrder: [10, 20, 30, ...]
  → POST /api/invest/pool/reorder (body: [{id, displayOrder}, ...])
    → InvestService.reorder → repository update N 次 → @CacheEvict
  → 200 OK
  → 表格保持新顺序
  → 失败 → 回滚 + toast 报错
```

### 6.3 OCR 批量导入

```
admin pool-crud panel → 点「📷 截图批量导入」
  → OCR modal 打开 → 上传图片
  → POST /api/invest/pool/import-image (multipart)
    → OcrService.parseImage → 不入库,返回识别结果数组
  → 显示表格,每行有勾选框 + 可编辑
  → 用户勾选 N 条 → 点「批量入库」
  → POST /api/invest/pool/batch-import (body: 勾选的列表)
    → OcrService.batchImport → 逐条 save + @CacheEvict
  → 200 OK → modal 关闭 + 表格 reload
```

## 7. 性能 & 缓存

- 复用刚加的 `stockPool` 30s Caffeine cache
- 所有写操作(add/update/remove/patch/reorder/seed/refresh/ocr-import)都已加 `@CacheEvict("stockPool", allEntries=true)`,管理动作后 0 延迟看到结果
- 拖拽 reorder 一次提交 N 个 UPDATE,事务内执行(N ≤ 50),<< 1s
- admin panel 列表用本地 state + 局部更新,操作后不重拉全部

## 8. 测试策略

按 `gupiao-quant` memory 提醒:改 entity/schema/DDL/批量入库必须 TDD。本次**不动 schema**,但**新增 reorder API + 批量更新** + **前端批量 UI**,需 TDD。

### 8.1 后端

- `InvestControllerReorderAuthTest`(新建):
  - 无 token → 401
  - USER 角色 → 403
  - MANAGER/ADMIN + 空 body → 400
  - MANAGER/ADMIN + 非法 id → 400
  - MANAGER/ADMIN + 合法 body → 200,repository.update 被调用 N 次,@CacheEvict 触发

- `InvestServiceReorderTest`(新建,可选):
  - mock repository,验证 displayOrder 写入参数正确
  - 验证异常时事务回滚

- 跑 `mvn test` 全部相关测试通过(预计 60+ 用例)

### 8.2 前端

- 手动验证(项目无 e2e 框架):
  - admin-users.html 侧边栏新增「股票池」入口 → 点击切换 panel
  - 三个分类 tab 切换正常,计数显示正确
  - 拖拽 1 行 → 1 次 POST reorder → 表格顺序保留 + displayOrder 自动重排为 10/20/30
  - 新增 modal → 填字段 → 保存 → 表格新增一行
  - 编辑 modal → 修改字段 → 保存 → 表格 cell 更新
  - 删除按钮 → confirm → 表格移除一行
  - memo cell 点击 → 内联编辑 → 失焦保存
  - OCR 批量导入 → 上传图片 → 识别结果显示 → 勾选 → 入库
  - 搜索框输入 → 表格实时过滤
  - invest.html 反复刷新确认已无管理入口(普通用户看不到,ADMIN 也没快捷入口)

## 9. 涉及文件清单

| 文件 | 改动 |
|---|---|
| `InvestController.java` | + `reorder` 端点 |
| `InvestService.java` | + `reorder` 方法 + `@CacheEvict` |
| `InvestStockPoolRepository.java` | + `@Modifying @Query` 批量更新 |
| `src/test/.../InvestControllerReorderAuthTest.java` | + 新建 |
| `src/main/resources/static/admin-users.html` | + panel-poolcrud DOM + CSS + sidebar 入口 |
| `src/main/resources/static/js/admin-poolcrud.js` | + 新建(独立文件) |
| `src/main/resources/static/invest.html` | - 删除管理按钮 + modal |
| `src/main/resources/static/js/invest.js` | - 删除所有写操作 JS |
| `docs/superpowers/specs/2026-07-01-invest-pool-admin-design.md` | + 本文档 |

## 10. 实施步骤

1. 后端:实现 `reorder` 端点 + service + repository + 单元测试
2. 后端:`mvn test` 全过
3. 前端:写 `admin-poolcrud.js` + admin-users.html 加 panel
4. 前端:invest.html + invest.js 删管理入口
5. 本地:`./restart.sh` 部署,手动验证
6. commit + push 到 origin
7. 远端 ssh `git pull` + `./restart.sh` 部署线上
8. 线上 curl + 手动验证一次

## 11. 风险与回滚

- **风险 1**: 拖拽 UX 在表格列较多时性能差 → 缓解:池子规模 ≤ 50,HTML5 native drag 够用;若不行再降级到箭头按钮
- **风险 2**: OCR 上传大图超时 → 缓解:沿用现有 `OcrService` 的处理逻辑(可能已有 size limit,需复查)
- **回滚方案**: 单 commit 可 revert;前端删的代码都在 git history 里