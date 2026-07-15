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
