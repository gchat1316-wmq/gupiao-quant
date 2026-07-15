# 股票财务数据查询系统

基于 Spring Boot 3 + MySQL 8 + 原生 H5/CSS/JS + Chart.js，支持 PC 和移动端的股票核心财务指标对比分析。

## 功能

- 多股票输入（按逗号分隔，支持名称或代码）
- 核心财务指标对比表：毛利率、营收同比、扣非同比、扣非TTM
- 指标切换 + 多股票折线对比图
- 一键下载图表 PNG
- 响应式布局（PC、平板、手机 H5）

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Spring Boot 3.2 / Spring Data JPA / Java 17 |
| 数据库 | MySQL 8.0 (utf8mb4) |
| 前端 | 原生 HTML5 + CSS3 + 原生 JS + Chart.js 4 (CDN) |

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

## 快速开始

### 1. 初始化数据库

```bash
mysql -uroot -p < sql/schema.sql
mysql -uroot -p < sql/sample_data.sql
```

`sql/schema.sql` 会创建：

- `trade_stock_financial`（用户已有的季度财务表）
- `trade_stock_info`（股票基础信息表，用于支持按名称查询）

`sql/sample_data.sql` 会写入截图中的 `贵州茅台 (600519)` 与 `佰维存储 (688525)` 示例数据。

### 2. 修改数据库连接

`src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/gupiao_quant?...
    username: root
    password: root
```

### 3. 启动应用

```bash
mvn spring-boot:run
```

打开浏览器或手机访问：<http://localhost:8080/>

## 接口文档

### GET `/api/stock/financial`

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `keywords` | 是 | 股票名称或代码，多个用逗号分隔。如 `贵州茅台,佰维存储` 或 `600519,688525` |
| `quarters` | 否 | 返回最近多少个季度，默认 15 |

示例：

```
GET /api/stock/financial?keywords=贵州茅台,佰维存储&quarters=15
```

返回：

```json
{
  "requested": 2,
  "matched": 2,
  "notFound": [],
  "stocks": [
    {
      "stockCode": "600519",
      "stockName": "贵州茅台",
      "quarters": [
        {
          "quarter": "26Q1",
          "reportDate": "2026-03-31",
          "grossMargin": 88.46,
          "revenueYoy": 6.34,
          "deductedNetProfitYoy": 1.45,
          "deductedNetProfitTtm": 82683000000.00
        }
      ]
    }
  ]
}
```

## 目录结构

```
.
├── pom.xml
├── sql
│   ├── schema.sql
│   └── sample_data.sql
└── src
    └── main
        ├── java/com/quant
        │   ├── GupiaoQuantApplication.java
        │   ├── controller/        // REST 接口
        │   ├── dto/               // 响应 DTO
        │   ├── entity/            // JPA 实体
        │   ├── repository/        // Spring Data 仓库
        │   └── service/           // 业务逻辑
        └── resources
            ├── application.yml
            └── static/            // 前端静态资源
                ├── index.html
                ├── css/style.css
                └── js/app.js
```

## H5 兼容性

- `<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover" />`
- 响应式断点：768px / 480px
- 表格使用横向滚动，避免移动端挤压
- `Chart.js` `responsive: true` 适配各种屏幕
