#!/usr/bin/env bash
# dev.sh — 一键启动后端（dev profile）+ 前端开发服务器（设计第 5 节命令约定）。
#
# 用法：bash scripts/dev.sh
# 环境变量：
#   SERVER_PORT      后端端口，默认 18095
#   FRONTEND_PORT    前端端口，默认 15195
#   LIB_JAVA_HOME    覆盖 JDK（见 env.sh）
#   LIB_DEV_READY_TIMEOUT  等待后端就绪的秒数，默认 120
#
# 行为：先检查两个端口是否被占用；启动 mvn spring-boot:run 并轮询
# GET /api/auth/csrf（预期 204）；就绪后启动 npm run dev；Ctrl-C 或任一进程
# 退出时清理全部子进程。兼容 macOS 自带 bash 3.2。

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$ROOT"

# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh" || { echo "[dev.sh] 环境校验失败，退出。" >&2; exit 1; }

SERVER_PORT="${SERVER_PORT:-18095}"
FRONTEND_PORT="${FRONTEND_PORT:-15195}"
READY_TIMEOUT="${LIB_DEV_READY_TIMEOUT:-120}"
export SERVER_PORT FRONTEND_PORT
export VITE_API_PROXY_TARGET="${VITE_API_PROXY_TARGET:-http://localhost:${SERVER_PORT}}"

port_in_use() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

check_port_free() {
  local port="$1" name="$2" var="$3"
  if port_in_use "$port"; then
    echo "[dev.sh] 错误：${name}端口 ${port} 已被占用：" >&2
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >&2 || true
    echo "[dev.sh] 请停止占用进程，或用环境变量 ${var}=<其他端口> 重新运行。" >&2
    return 1
  fi
  return 0
}

check_port_free "$SERVER_PORT" "后端" "SERVER_PORT" || exit 1
check_port_free "$FRONTEND_PORT" "前端" "FRONTEND_PORT" || exit 1

if [ ! -f "$ROOT/backend/pom.xml" ]; then
  echo "[dev.sh] 错误：缺少 backend/pom.xml，后端尚未实现完成。" >&2
  exit 1
fi
if [ ! -f "$ROOT/frontend/package.json" ]; then
  echo "[dev.sh] 错误：缺少 frontend/package.json，前端尚未实现完成。" >&2
  exit 1
fi

BACKEND_PID=""
FRONTEND_PID=""
CLEANED=0

# 递归杀掉进程树（mvn 会派生 java 子进程，npm 会派生 node 子进程）。
kill_tree() {
  local pid="$1" sig="$2" child
  for child in $(pgrep -P "$pid" 2>/dev/null || true); do
    kill_tree "$child" "$sig"
  done
  kill -"$sig" "$pid" 2>/dev/null || true
}

cleanup() {
  if [ "$CLEANED" -eq 1 ]; then return 0; fi
  CLEANED=1
  trap - INT TERM EXIT
  echo ""
  echo "[dev.sh] 正在停止子进程..."
  local pid i alive
  for pid in "$FRONTEND_PID" "$BACKEND_PID"; do
    if [ -n "$pid" ]; then kill_tree "$pid" TERM; fi
  done
  i=0
  while [ "$i" -lt 10 ]; do
    alive=0
    for pid in "$FRONTEND_PID" "$BACKEND_PID"; do
      if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then alive=1; fi
    done
    if [ "$alive" -eq 0 ]; then break; fi
    sleep 1
    i=$((i + 1))
  done
  for pid in "$FRONTEND_PID" "$BACKEND_PID"; do
    if [ -n "$pid" ]; then kill_tree "$pid" KILL; fi
  done
  wait 2>/dev/null || true
  echo "[dev.sh] 已退出。"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [ ! -d "$ROOT/frontend/node_modules" ]; then
  echo "[dev.sh] frontend/node_modules 不存在，先执行 npm --prefix frontend ci ..."
  npm --prefix frontend ci
fi

echo "[dev.sh] 启动后端：mvn -f backend/pom.xml spring-boot:run（dev profile，端口 ${SERVER_PORT}）..."
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev &
BACKEND_PID=$!

echo "[dev.sh] 等待后端就绪：GET http://localhost:${SERVER_PORT}/api/auth/csrf（最多 ${READY_TIMEOUT}s）..."
deadline=$(( $(date +%s) + READY_TIMEOUT ))
ready=0
while [ "$(date +%s)" -lt "$deadline" ]; do
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "[dev.sh] 错误：后端进程提前退出，请查看上方 Maven 输出。" >&2
    exit 1
  fi
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 \
    "http://localhost:${SERVER_PORT}/api/auth/csrf" 2>/dev/null || true)
  if [ "$code" = "204" ]; then
    ready=1
    break
  fi
  sleep 2
done
if [ "$ready" -ne 1 ]; then
  echo "[dev.sh] 错误：${READY_TIMEOUT}s 内后端未就绪（/api/auth/csrf 未返回 204）。" >&2
  exit 1
fi
echo "[dev.sh] 后端已就绪。"

echo "[dev.sh] 启动前端：npm --prefix frontend run dev（端口 ${FRONTEND_PORT}）..."
npm --prefix frontend run dev &
FRONTEND_PID=$!

echo "[dev.sh] 前端地址：http://localhost:${FRONTEND_PORT}"
echo "[dev.sh] 按 Ctrl-C 同时停止前端和后端。"

# 监控两个子进程，任一退出则整体收尾（bash 3.2 没有 wait -n，用轮询）。
while :; do
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "[dev.sh] 后端进程已退出，停止前端。" >&2
    exit 1
  fi
  if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
    echo "[dev.sh] 前端进程已退出，停止后端。" >&2
    exit 1
  fi
  sleep 2
done