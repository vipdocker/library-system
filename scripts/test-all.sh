#!/usr/bin/env bash
# test-all.sh — 全量验证（设计第 5 节命令约定）。
#
# 用法：bash scripts/test-all.sh
# 顺序：后端 mvn verify → （必要时 npm ci）→ 前端 lint / typecheck / test / build → e2e。
# 任一步失败立即停止并返回该步的非 0 退出码。
# 注意：e2e 使用独立端口 18096/15196 和临时数据库（由 playwright.config.ts 负责），
#       运行期间不要同时运行 scripts/dev.sh 之外的其他 e2e。

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$ROOT"

# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh" || { echo "[test-all.sh] 环境校验失败，退出。" >&2; exit 1; }

step() {
  echo ""
  echo "==> $*"
}

step "后端构建与测试：mvn -f backend/pom.xml verify"
mvn -f backend/pom.xml verify

if [ ! -d "$ROOT/frontend/node_modules" ]; then
  step "frontend/node_modules 不存在，先执行：npm --prefix frontend ci"
  npm --prefix frontend ci
fi

step "前端 lint：npm --prefix frontend run lint"
npm --prefix frontend run lint

step "前端类型检查：npm --prefix frontend run typecheck"
npm --prefix frontend run typecheck

step "前端单元/组件测试：npm --prefix frontend run test"
npm --prefix frontend run test

step "前端构建：npm --prefix frontend run build"
npm --prefix frontend run build

step "端到端测试：npm --prefix frontend run e2e"
npm --prefix frontend run e2e

echo ""
echo "[test-all.sh] 全部检查通过。"