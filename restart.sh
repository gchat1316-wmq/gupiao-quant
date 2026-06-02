#!/bin/bash
set -e

PORT=8080
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="$APP_DIR/app.log"
JAR_PATTERN="gupiao-quant"

echo "[1/3] 检查端口 $PORT ..."
PID=$(lsof -ti tcp:$PORT 2>/dev/null || true)
if [ -n "$PID" ]; then
  echo "      端口 $PORT 被 PID $PID 占用，执行 kill -9 ..."
  kill -9 $PID
  sleep 1
  echo "      已终止 PID $PID"
else
  echo "      端口 $PORT 空闲"
fi

echo "[2/3] 构建项目 ..."
cd "$APP_DIR"
mvn package -q -DskipTests

echo "[3/3] 启动应用，日志输出到 $LOG_FILE ..."
nohup java -jar target/gupiao-quant-*.jar \
  --spring.profiles.active=default \
  > "$LOG_FILE" 2>&1 &
NEW_PID=$!
echo "      已启动，PID=$NEW_PID"

echo "      等待服务就绪 ..."
for i in $(seq 1 30); do
  sleep 1
  if curl -s -o /dev/null -w "%{http_code}" "http://localhost:$PORT/gp/" 2>/dev/null | grep -q "200"; then
    echo "      服务已就绪 -> http://localhost:$PORT/gp/"
    exit 0
  fi
done

echo "      [警告] 30秒内未检测到服务响应，请查看日志：tail -f $LOG_FILE"
exit 1
