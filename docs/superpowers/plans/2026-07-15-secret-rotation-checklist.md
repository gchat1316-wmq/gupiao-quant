# Manual Secret Rotation Checklist — 给东哥

Sprint 1 把 application.yml 里的明文密钥清空了，但 git history 里的值还在。把全部 8 个凭据视为已泄漏，必须全部轮换。

每个条目：重新生成 → 写到生产服务器 `/etc/gupiao-quant/secrets.env` → 重启（`./restart.sh`）→ 验证相关模块 → 勾掉。

如果想直接改 git history 抹掉，跑 `git filter-repo` 按凭据逐条清洗 + force-push + 通知所有协作者重新 clone。这是重锤，**默认建议走 rotation 而非 history rewrite**。

## Rotation actions

- [ ] **DB password** (`DB_PASSWORD`)
  - mysql: `ALTER USER 'wucai_app'@'%' IDENTIFIED BY '<new-strong-32+chars>'; FLUSH PRIVILEGES;`
  - 更新 `/etc/gupiao-quant/secrets.env`
  - 验证: `curl http://localhost:8080/gp/api/stock-analysis/health`

- [ ] **JWT secret** (`JWT_SECRET`)
  - 生成: `openssl rand -base64 48`
  - **轮换会让所有在线用户的 token 失效** —— 重启前通知全员重新登录
  - 更新 env，重启，验证重新登录流程

- [ ] **MiniMax API key** (`AI_MINIMAX_KEY`)
  - minimaxi.com → API → rotate key
  - 验证: 触发任意 `/api/prosperity-strong/**` 端点触发 LLM 调用

- [ ] **SenseNova API key** (`SENSENOVA_API_KEY` / `AI_SENSENOVA_KEY`)
  - token.sensenova.cn → API keys → 旋转
  - 验证: `/api/stock-analysis/health` 和一个真实分析请求

- [ ] **Tavily API key** (`TAVILY_API_KEY`)
  - tavily.com → API keys → 旋转
  - 验证: 触发一次新闻检索

- [ ] **Server酱 send key** (`SERVER_CHAN_SEND_KEY`)
  - sct.ftqq.com → 密钥管理 → 旋转
  - 验证: 触发一次通知（测试端点或等下一个盘中报警）

- [ ] **飞书 wish-pool webhook** (`WISH_POOL_FEISHU_WEBHOOK_URL`)
  - 飞书群 → 删除旧机器人 → 添加新机器人 → 复制新 webhook URL
  - 验证: 通过公开 UI 提交一个 wish

- [ ] **TDX API key** (`TDX_API_KEY`)
  - TDX 控制台 → 旋转
  - 验证: `/api/prosperity-strong/...` 实时行情路径

- [ ] **MySQL server SSL 证书**（仅在启用 `DB_USE_SSL=true` 时）
  - 给 MySQL 服务器生成 / 拿到 CA 签名证书
  - 服务端 `require_secure_transport=ON`
  - 挂载 CA 证书到应用服务器 `/etc/ssl/mysql-ca.pem`
  - 设 `DB_USE_SSL=true` 进 `/etc/gupiao-quant/secrets.env`
  - 重启，验证：`mysql --ssl -h 43.140.208.165 -u ... -e 'SHOW STATUS LIKE "Ssl_cipher";'`
  - **MySQL 服务器必须先开 SSL**，否则 `DB_USE_SSL=true` 会直接拒连

## 轮换完成后

- [ ] 更新 `docs/superpowers/plans/2026-07-15-secret-inventory.md`，把 value 列改成 `[已轮换 YYYY-MM-DD]`
- [ ] 考虑 `git filter-repo --invert-paths --path src/main/resources/application.yml` 清理历史（会改写所有 commit，对所有协作者是大破坏）
