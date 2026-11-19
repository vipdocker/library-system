#!/usr/bin/env bash
# reset-db.sh — 停机状态下删除本地 SQLite 数据库文件（设计第 3/6 节）。
#
# 用法：
#   bash scripts/reset-db.sh          # 交互确认后删除
#   bash scripts/reset-db.sh --yes    # 跳过确认
#
# 环境变量：
#   SERVER_PORT   后端端口，默认 18095；检测到该端口仍在监听则拒绝执行。
#   LIB_DB_PATH   数据库路径，默认 ./data/library.db（相对后端工作目录 backend/，
#                 即 backend/data/library.db）；绝对路径按原样使用。
#
# 删除 <db>、<db>-wal、<db>-shm、<db>-journal。不依赖 Java/Node。

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

SERVER_PORT="${SERVER_PORT:-18095}"

ASSUME_YES=0
for arg in "$@"; do
  case "$arg" in
    --yes|-y) ASSUME_YES=1 ;;
    *)
      echo "[reset-db.sh] 错误：未知参数 '$arg'（仅支持 --yes）。" >&2
      exit 2
      ;;
  esac
done

# 后端仍在运行时拒绝执行，避免删除正在使用的数据库。
if lsof -nP -iTCP:"$SERVER_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "[reset-db.sh] 拒绝执行：端口 ${SERVER_PORT} 上仍有进程在监听（后端可能正在运行）。" >&2
  echo "[reset-db.sh] 请先停止后端（在 scripts/dev.sh 的终端按 Ctrl-C），再重试。" >&2
  exit 1
fi

DB_PATH="${LIB_DB_PATH:-./data/library.db}"
case "$DB_PATH" in
  /*) : ;;                                # 绝对路径按原样使用
  *)  DB_PATH="$ROOT/backend/$DB_PATH" ;;   # 相对路径以后端工作目录 backend/ 为基准
esac

existing=""
for f in "$DB_PATH" "$DB_PATH-wal" "$DB_PATH-shm" "$DB_PATH-journal"; do
  if [ -e "$f" ]; then
    existing="$existing$f
"
  fi
done

if [ -z "$existing" ]; then
  echo "[reset-db.sh] 数据库文件不存在，无需重置：$DB_PATH"
  exit 0
fi

echo "[reset-db.sh] 将删除以下数据库文件（不可恢复）："
printf '%s' "$existing" | sed 's/^/  /'

if [ "$ASSUME_YES" -ne 1 ]; then
  printf '[reset-db.sh] 确认删除？输入 y 继续，其他任意输入取消: '
  answer=""
  read -r answer || answer=""
  case "$answer" in
    y|Y|yes|YES) : ;;
    *)
      echo "[reset-db.sh] 已取消，未删除任何文件。"
      exit 1
      ;;
  esac
fi

for f in "$DB_PATH" "$DB_PATH-wal" "$DB_PATH-shm" "$DB_PATH-journal"; do
  if [ -e "$f" ]; then
    rm -f -- "$f"
    echo "[reset-db.sh] 已删除：$f"
  fi
done
echo "[reset-db.sh] 完成。下次启动后端时会自动重建数据库并完成迁移。"