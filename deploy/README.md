# Deploy runbook

## 一次性配置

```bash
# 1. 准备密钥文件（项目根；已被 .gitignore 忽略，永远不会被提交）
cp deploy/secrets.env.example ./secrets.env
chmod 600 ./secrets.env
vim ./secrets.env   # 填入真实密钥

# 2. （可选）DB SSL 时，准备 CA 证书
sudo mkdir -p /etc/ssl
sudo cp <your-ca-cert>.pem /etc/ssl/mysql-ca.pem

# 3. 部署代码 + 启动
./restart.sh
```

## 启动失败排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `IllegalStateException: Required configuration is missing...` | env var 未传入 | 编辑 `./secrets.env` 后 `./restart.sh` |
| `Communications link failure ... SSL ...` | `DB_USE_SSL=true` 但 MySQL 未启用 SSL | 改回 `DB_USE_SSL=false` 或先在 MySQL 侧启用 |
| `401 Unauthorized` 全部变多 | `JWT_SECRET` 轮换导致旧 token 失效 | 让用户重新登录；JWT 不可平滑轮换 |
| gzip 不生效 | 浏览器 `Accept-Encoding` 缺失 | 抓包确认 `Content-Encoding: gzip` 头 |

## Sprint 1 概览

2026-07-15 完成的安全清理，详见 `docs/superpowers/plans/2026-07-15-security-cleanup-sprint-1.md`。
密钥轮换清单：`docs/superpowers/plans/2026-07-15-secret-rotation-checklist.md`。
