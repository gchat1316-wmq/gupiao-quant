#!/bin/bash
# gupiao-quant 启动脚本 (防呆版)
# 防呆点:
#   1. 强制 chown target/ (解决 root 编辑后 ubuntu 跑 mvn 写不进去)
#   2. 清理残留 baostock 进程 (避免抢占 socket)
#   3. 自动备份 app.log (避免日志无限膨胀)
#   4. mvn clean package (每次全新构建, 避免脏数据)
#   5. 杀进程后等 2s (确保端口释放)
#   6. 服务就绪检测 (最多 60s)

set -e

PORT=8080
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$APP_DIR"

# ============================================================
# 0. 环境自检
# ============================================================
echo "[0/4] 环境自检 ..."
CURRENT_USER=$(whoami)
CURRENT_GROUP=$(id -gn)
echo "      用户: $CURRENT_USER ($CURRENT_GROUP)"
echo "      目录: $APP_DIR"

# 备份老日志 (防膨胀)
if [ -f app.log ]; then
    LOG_SIZE=$(du -h app.log | awk '{print $1}')
    if [ -f app.log.1 ] || [ -f app.log.2 ] || [ -f app.log.3 ]; then
        # 轮转: app.log.3 -> 删, app.log.2 -> .3, app.log.1 -> .2
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
echo "[1/4] 清理残留进程 ..."
# 杀掉所有 baostock 子进程 (避免抢占 baostock socket)
BAOSTOCK_PIDS=$(pgrep -f "baostock" 2>/dev/null || true)
if [ -n "$BAOSTOCK_PIDS" ]; then
    echo "      发现残留 baostock 进程: $BAOSTOCK_PIDS, kill -9"
    echo "$BAOSTOCK_PIDS" | xargs -r kill -9 2>/dev/null || true
fi
# 杀掉 playwright 残留 (PDF 渲染)
PLAYWRIGHT_PIDS=$(pgrep -f "playwright" 2>/dev/null || true)
if [ -n "$PLAYWRIGHT_PIDS" ]; then
    echo "      发现残留 playwright 进程: $PLAYWRIGHT_PIDS, kill -9"
    echo "$PLAYWRIGHT_PIDS" | xargs -r kill -9 2>/dev/null || true
fi

# 杀掉旧 java 进程 (端口 8080 占用)
PID=$(lsof -ti tcp:$PORT 2>/dev/null || true)
if [ -n "$PID" ]; then
    echo "      端口 $PORT 被 PID $PID 占用, kill -9"
    kill -9 $PID 2>/dev/null || true
    sleep 2  # 等端口彻底释放
    echo "      已终止, 等待 2s 让端口释放"
else
    echo "      端口 $PORT 空闲"
fi

# ============================================================
# 2. 修复文件权限 (防呆核心: 解决 root 编辑后 ubuntu 跑 mvn 失败)
# ============================================================
echo "[2/4] 修复文件权限 ..."
# 强制把所有 target/ 下的文件 chown 给当前用户 (需要 sudo 时静默失败)
chown -R "$CURRENT_USER:$CURRENT_GROUP" target/ src/ pom.xml 2>/dev/null || {
    echo "      ⚠️  chown 部分失败 (可能需要 sudo), 继续构建"
}
# 给所有 .sh 脚本可执行权限
find . -maxdepth 2 -name "*.sh" -exec chmod +x {} \; 2>/dev/null || true
echo "      权限修复完成"

# ============================================================
# 3. 构建 (mvn clean package - 全新构建, 避免脏数据)
# ============================================================
echo "[3/4] 构建项目 (mvn clean package) ..."
mvn clean package -q -DskipTests
if [ ! -f target/gupiao-quant-1.0.0.jar ]; then
    echo "      ❌ 构建失败: jar 包未生成"
    exit 1
fi
JAR_SIZE=$(du -h target/gupiao-quant-1.0.0.jar | awk '{print $1}')
echo "      ✓ 构建成功: $JAR_SIZE"

# ============================================================
# 4. 启动 + 就绪检测
# ============================================================
echo "[4/4] 启动应用, 日志输出到 app.log ..."
nohup java -jar target/gupiao-quant-1.0.0.jar \
    --spring.profiles.active=default \
    > app.log 2>&1 &
NEW_PID=$!
echo "      已启动, PID=$NEW_PID"

# 就绪检测 (最多 60s)
echo "      等待服务就绪 ..."
for i in $(seq 1 60); do
    sleep 1
    if curl -s -o /dev/null -w "%{http_code}" "http://localhost:$PORT/gp/" 2>/dev/null | grep -q "200"; then
        echo "      ✅ 服务已就绪 -> http://localhost:$PORT/gp/ (耗时 ${i}s)"
        echo "      健康检查: curl http://localhost:$PORT/gp/api/stock-analysis/health"
        exit 0
    fi
done

# 60s 还没起来, 抓最后日志
echo "      ❌ 60s 内未检测到服务响应"
echo "      === app.log 最后 20 行 ==="
tail -20 app.log
exit 1
