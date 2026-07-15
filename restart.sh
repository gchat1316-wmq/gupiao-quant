#!/bin/bash
# gupiao-quant 启动脚本 (防呆加固版 v2026-06-15)
# ===========================================
# 防呆点 (基础):
#   1. 强制 chown target/ (解决 root 编辑后 ubuntu 跑 mvn 写不进去)
#   2. 清理残留 baostock 进程 (避免抢占 socket)
#   3. 自动备份 app.log (避免日志无限膨胀)
#   4. mvn clean package (每次全新构建, 避免脏数据)
#   5. 杀进程后等 2s (确保端口释放)
#   6. 服务就绪检测 (最多 60s)
#
# 加固点 (v2026-06-15, 防 commit c06532f 类问题):
#   7. **JAR-时间戳漂移检测**：jar mtime > 当前进程启动时间 → 提示需要重启
#   8. **Git 漂移检测**：源码有未提交/未推送的改动 → 警告 + 显示 diff
#   9. **PID 文件跟踪**：写入 run.pid，watchdog 可基于此判断
#   10. **磁盘预检**：避免日志满导致服务再炸
#   11. **外部可访问性冒烟**：就绪后顺便测 aidaily.dpdns.org/gp/
#   12. **失败诊断包**：失败时一键抓取 (日志/磁盘/进程/dmesg)

set -e

PORT=8080
CTX_PATH="gp"
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$APP_DIR"
JAR="target/gupiao-quant-1.0.0.jar"
PID_FILE="$APP_DIR/run.pid"
EXTERNAL_URL="https://aidaily.dpdns.org/gp/"

# ============================================================
# 0. 环境自检 + 加固项 7 (jar 时间戳漂移检测)
# ============================================================
echo "[0/8] 环境自检 + 漂移检测 ..."

CURRENT_USER=$(whoami)
CURRENT_GROUP=$(id -gn)
echo "      用户: $CURRENT_USER ($CURRENT_GROUP)"
echo "      目录: $APP_DIR"

# ---- 加固 #10: 磁盘预检 ----
DISK_USED=$(df "$APP_DIR" | awk 'NR==2 {print $5}' | tr -d '%')
if [ "$DISK_USED" -ge 90 ]; then
    echo "      ⚠️  磁盘使用 ${DISK_USED}%, 接近警戒线, 自动清理大日志"
    find "$APP_DIR" -maxdepth 1 -name "app.log.*" -size +100M -exec rm -f {} \; 2>/dev/null || true
fi
echo "      磁盘使用: ${DISK_USED}%"

# ---- 加固 #7: jar 时间戳 vs 进程启动时间漂移检测 ----
if [ -f "$JAR" ] && [ -f "$PID_FILE" ]; then
    CURRENT_PID=$(cat "$PID_FILE" 2>/dev/null || true)
    if [ -n "$CURRENT_PID" ] && kill -0 "$CURRENT_PID" 2>/dev/null; then
        JAR_MTIME_EPOCH=$(stat -c %Y "$JAR" 2>/dev/null || echo 0)
        PROC_MTIME_EPOCH=$(ps -o lstart= -p "$CURRENT_PID" 2>/dev/null | xargs -I{} date -d "{}" +%s 2>/dev/null || echo 0)
        if [ -n "$PROC_MTIME_EPOCH" ] && [ "$JAR_MTIME_EPOCH" -gt "$PROC_MTIME_EPOCH" ]; then
            DRIFT_SEC=$((JAR_MTIME_EPOCH - PROC_MTIME_EPOCH))
            DRIFT_MIN=$((DRIFT_SEC / 60))
            echo "      ⚠️  JAR 比进程新 ${DRIFT_SEC}s (${DRIFT_MIN}min), 说明 jar 已重打但进程未重启"
            echo "         这正是 2026-06-15 commit c06532f 出问题的根因！"
            echo "         主动 kill PID $CURRENT_PID 以消除漂移 ..."
            kill -9 "$CURRENT_PID" 2>/dev/null || true
            sleep 2
            rm -f "$PID_FILE"
        fi
    else
        rm -f "$PID_FILE"
    fi
fi

# 备份老日志 (防膨胀)
if [ -f app.log ]; then
    LOG_SIZE=$(du -h app.log | awk '{print $1}')
    if [ -f app.log.1 ] || [ -f app.log.2 ] || [ -f app.log.3 ]; then
        rm -f app.log.3
        [ -f app.log.2 ] && mv app.log.2 app.log.3
        [ -f app.log.1 ] && mv app.log.1 app.log.2
    fi
    mv app.log app.log.1
    echo "      已备份 app.log ($LOG_SIZE) -> app.log.1"
fi

# ============================================================
# 1. 清理残留进程 (baostock / playwright / java)
# ============================================================
echo "[1/8] 清理残留进程 ..."
BAOSTOCK_PIDS=$(pgrep -f "baostock" 2>/dev/null || true)
if [ -n "$BAOSTOCK_PIDS" ]; then
    echo "      发现残留 baostock 进程: $BAOSTOCK_PIDS, kill -9"
    echo "$BAOSTOCK_PIDS" | xargs -r kill -9 2>/dev/null || true
fi
PLAYWRIGHT_PIDS=$(pgrep -f "playwright" 2>/dev/null || true)
if [ -n "$PLAYWRIGHT_PIDS" ]; then
    echo "      发现残留 playwright 进程: $PLAYWRIGHT_PIDS, kill -9"
    echo "$PLAYWRIGHT_PIDS" | xargs -r kill -9 2>/dev/null || true
fi

PID=$(lsof -ti tcp:$PORT 2>/dev/null || true)
if [ -n "$PID" ]; then
    echo "      端口 $PORT 被 PID $PID 占用, kill -9"
    kill -9 $PID 2>/dev/null || true
    sleep 2
    echo "      已终止, 等待 2s 让端口释放"
else
    echo "      端口 $PORT 空闲"
fi

# ============================================================
# 2. 修复文件权限
# ============================================================
echo "[2/8] 修复文件权限 ..."
chown -R "$CURRENT_USER:$CURRENT_GROUP" target/ src/ pom.xml 2>/dev/null || {
    echo "      ⚠️  chown 部分失败 (可能需要 sudo), 继续构建"
}
find . -maxdepth 2 -name "*.sh" -exec chmod +x {} \; 2>/dev/null || true
echo "      权限修复完成"

# ============================================================
# 3. 构建
# ============================================================
echo "[3/8] 构建项目 (mvn clean package) ..."
mvn clean package -q -DskipTests
if [ ! -f "$JAR" ]; then
    echo "      ❌ 构建失败: jar 包未生成"
    exit 1
fi
JAR_SIZE=$(du -h "$JAR" | awk '{print $1}')
echo "      ✓ 构建成功: $JAR_SIZE"

# ============================================================
# 4. 加固 #8: Git 漂移检测 (源码 vs jar)
# ============================================================
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

echo "[4/8] Git 漂移检测 ..."
if command -v git >/dev/null 2>&1 && [ -d .git ]; then
    UNCOMMITTED=$(git status --porcelain 2>/dev/null | wc -l | tr -d ' \n')
    UNPUSHED=$(git log --oneline @{u}.. 2>/dev/null | wc -l | tr -d ' \n')
    UNPUSHED=${UNPUSHED:-0}
    UNCOMMITTED=${UNCOMMITTED:-0}
    if [ "${UNCOMMITTED:-0}" -gt 0 ] 2>/dev/null; then
        echo "      ⚠️  有 ${UNCOMMITTED} 个未提交的改动"
        git status --short 2>/dev/null | head -5 | sed 's/^/         /'
    fi
    if [ "${UNPUSHED:-0}" -gt 0 ] 2>/dev/null; then
        echo "      ⚠️  有 ${UNPUSHED} 个本地 commit 未推送到 origin"
        echo "         (东哥手动推送, AI 不自动 push)"
    fi
    if [ "${UNCOMMITTED:-0}" -eq 0 ] 2>/dev/null && [ "${UNPUSHED:-0}" -eq 0 ] 2>/dev/null; then
        echo "      ✓ Git 工作区干净 (无未提交/未推送)"
    fi
else
    echo "      (非 git 仓库, 跳过漂移检测)"
fi

# ============================================================
# 5. 启动应用
# ============================================================
echo "[5/8] 启动应用, 日志输出到 app.log ..."
nohup java -Xmx512m -Xms512m -jar "$JAR" \
    --spring.profiles.active=prod \
    > app.log 2>&1 &
NEW_PID=$!
echo "$NEW_PID" > "$PID_FILE"
echo "      已启动, PID=$NEW_PID (写入 $PID_FILE)"

# ============================================================
# 6. 就绪检测 (内部)
# ============================================================
echo "[6/8] 等待服务就绪 (内部端口 $PORT) ..."
LOCAL_OK=0
for i in $(seq 1 60); do
    sleep 1
    if curl -s -o /dev/null -w "%{http_code}" "http://localhost:$PORT/$CTX_PATH/" 2>/dev/null | grep -q "200"; then
        echo "      ✅ 内部就绪 (耗时 ${i}s) -> http://localhost:$PORT/$CTX_PATH/"
        LOCAL_OK=1
        break
    fi
done

if [ "$LOCAL_OK" -ne 1 ]; then
    echo "      ❌ 60s 内未检测到内部端口响应"
    echo ""
    echo "      ========== 失败诊断包 =========="
    echo "      [磁盘]" && df -h "$APP_DIR" | tail -1
    echo "      [内存]" && free -h | head -2 | tail -1
    echo "      [java 进程]" && ps -ef | grep -E "gupiao-quant-1.0.0.jar" | grep -v grep | head -2
    echo "      [端口占用]" && lsof -i tcp:$PORT 2>/dev/null | head -3
    echo "      [app.log 最后 30 行]"
    tail -30 app.log 2>/dev/null | sed 's/^/         /'
    echo "      ================================="
    exit 1
fi

# ============================================================
# 7. 加固 #11: 外部可访问性冒烟测试
# ============================================================
echo "[7/8] 外部冒烟测试 ($EXTERNAL_URL) ..."
EXT_CODE=$(curl -sk -o /dev/null -w "%{http_code}" --max-time 10 "$EXTERNAL_URL" 2>/dev/null || echo "000")
case "$EXT_CODE" in
    200) echo "      ✅ 外部可达 (HTTP $EXT_CODE)" ;;
    302|301) echo "      ✅ 外部可达 (HTTP $EXT_CODE, 重定向)" ;;
    000) echo "      ⚠️  外部超时 (可能是 nginx/ddns 问题, 不影响内部)" ;;
    *)   echo "      ⚠️  外部返回 HTTP $EXT_CODE, 请检查 nginx/ddns" ;;
esac

HEALTH=$(curl -s "http://localhost:$PORT/$CTX_PATH/api/stock-analysis/health" 2>/dev/null | head -c 200)
if [ -n "$HEALTH" ]; then
    echo "      健康检查: $HEALTH"
fi

# ============================================================
# 8. 完成总结
# ============================================================
echo "[8/8] 启动完成 ✅"
echo "      ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "      内部:    http://localhost:$PORT/$CTX_PATH/"
echo "      外部:    $EXTERNAL_URL"
echo "      PID:     $NEW_PID (文件: $PID_FILE)"
echo "      JAR:     $JAR ($JAR_SIZE, $(stat -c '%y' "$JAR" 2>/dev/null | cut -d. -f1))"
echo "      日志:    tail -f $APP_DIR/app.log"
echo "      健康:    curl http://localhost:$PORT/$CTX_PATH/api/stock-analysis/health"
echo "      停服:    kill $NEW_PID  或  kill \$(cat $PID_FILE)"
echo "      ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
