#!/usr/bin/env bash
#
# 按 devDocs/sftp-test-plan.md §4 顺序执行自动化测试（不需要真机的部分）
#   L0 不变量 → L1 网关 RPC → L2 Web E2E（含构建/起服务/清理）→ L3 Electron
#
# 用法：
#   bash scripts/run-all-tests.sh
#   SKIP_ELECTRON=1 bash scripts/run-all-tests.sh     # 跳过 Electron（未装依赖时）
#   E2E_BUILD=1 bash scripts/run-all-tests.sh         # 强制重建 Web 产物
#   CONTINUE=1 bash scripts/run-all-tests.sh          # 失败不中止，跑完汇总
#
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

LOG_DIR="${TEST_LOG_DIR:-/tmp/kr-tests}"
mkdir -p "$LOG_DIR"
declare -a NAMES=() STATES=()
FAILED=0

log()  { printf '\n\033[1m[run-all] %s\033[0m\n' "$*"; }
step() { # step <name> <cmd...>
  local name="$1"; shift
  log "▶ $name"
  local f="$LOG_DIR/$(echo "$name" | tr ' /' '__').log"
  if "$@" >"$f" 2>&1; then
    echo "PASS | $name"; NAMES+=("$name"); STATES+=("PASS")
  else
    echo "FAIL | $name  (日志: $f)"; tail -20 "$f"; NAMES+=("$name"); STATES+=("FAIL"); FAILED=1
    [ "${CONTINUE:-0}" = "1" ] || { log "已中止（CONTINUE=1 可继续）"; exit 1; }
  fi
}
summary() {
  log "汇总"
  for i in "${!NAMES[@]}"; do printf '  %-6s %s\n' "${STATES[$i]}" "${NAMES[$i]}"; done
  [ "$FAILED" = "0" ] && echo "  → 全部通过" || echo "  → 存在失败"
}
trap 'summary' EXIT

# ---------- 工具链 ----------
resolve_node() {
  if [ -n "${NODE_BIN:-}" ] && [ -x "$NODE_BIN" ]; then NODE="$NODE_BIN"; return; fi
  local c
  for c in "$HOME/.gradle/nodejs/node-v22.0.0-darwin-x64/bin/node" "$(command -v node 2>/dev/null || true)" /usr/local/bin/node; do
    [ -n "$c" ] && [ -x "$c" ] && { NODE="$c"; return; }
  done
}
resolve_node
[ -n "${NODE:-}" ] || { echo "找不到 node"; exit 1; }
export PATH="$(dirname "$NODE"):$PATH"
log "node: $NODE ($($NODE -v))"

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  for c in "$HOME/Library/Java/JavaVirtualMachines/corretto-17"*/Contents/Home; do
    [ -x "$c/bin/java" ] && { export JAVA_HOME="$c"; break; }
  done
fi
[ -n "${JAVA_HOME:-}" ] && log "JAVA_HOME: $JAVA_HOME" || log "JAVA_HOME 未设置（若需构建请设置 JDK17）"

# ---------- L0 ----------
step "L0 不变量" bash scripts/check-invariants.sh

# ---------- L1 ----------
if ! curl -sf "http://127.0.0.1:18090/health" >/dev/null 2>&1; then
  log "启动网关"
  ( cd "$ROOT/sftp-gateway" && nohup "$NODE" server.js >"$LOG_DIR/gateway.log" 2>&1 </dev/null & echo $! >"$LOG_DIR/gateway.pid" )
  for _ in $(seq 1 40); do curl -sf "http://127.0.0.1:18090/health" >/dev/null 2>&1 && break; sleep 0.5; done
fi
step "L1 网关 RPC" bash -c "cd '$ROOT/sftp-gateway' && npm run --silent test:rpc"

# ---------- L2 ----------
# e2e 会按需构建、起服务、跑测试，并清理「它自己启动的」服务（已在跑的不受影响）
step "L2 Web E2E" bash -c "cd '$ROOT/sftp-gateway' && npm run --silent e2e"

# ---------- L3 ----------
if [ "${SKIP_ELECTRON:-0}" = "1" ]; then
  NAMES+=("L3 Electron"); STATES+=("SKIP"); echo "SKIP | L3 Electron（SKIP_ELECTRON=1）"
elif [ ! -f "$ROOT/electron/node_modules/electron/path.txt" ]; then
  NAMES+=("L3 Electron"); STATES+=("SKIP"); echo "SKIP | L3 Electron（未安装：cd electron && npm install）"
else
  step "L3 Electron 开发态" bash -c "cd '$ROOT/electron' && npm run --silent sync && npm test"
fi

exit "$FAILED"
