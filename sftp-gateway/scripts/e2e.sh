#!/usr/bin/env bash
#
# Kuikly SFTP Web 端全自动 E2E：构建 → 起服务 → 跑测试 → 清理
#
#   cd sftp-gateway && npm run e2e
#
# 全自动：JDK17/Node 自动探测；产物缺失才构建；只启动未监听的端口；
#         测试有硬超时；结束时只关本脚本启动的进程（E2E_KEEP=1 则保留）。
#
# 可选环境变量：
#   E2E_BUILD=1 强制重建前端产物 | E2E_KEEP=1 保留服务 | E2E_KEEP=1
#   E2E_TEST_TIMEOUT=300 测试超时秒数 | E2E_WORKDIR 工作目录
#   JAVA_HOME / NODE_BIN / CHROME_PATH / SFTP_*  同 test/sftp-web.test.js
#
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
GW_DIR="$ROOT/sftp-gateway"
WORK="${E2E_WORKDIR:-/tmp/kr-e2e}"
GATEWAY_PORT="${SFTP_GATEWAY_PORT:-18090}"
SHELL_PORT="${E2E_SHELL_PORT:-8080}"
TEST_TIMEOUT="${E2E_TEST_TIMEOUT:-300}"

ZIP="$ROOT/demo/build/outputs/kuikly/js/debug/local/nativevue2.zip"
H5JS="$ROOT/h5App/build/kotlin-webpack/js/developmentExecutable/h5App.js"

log() { printf '\n[e2e] %s\n' "$*"; }
fail() { printf '\n[e2e] FAILED: %s\n' "$*"; exit 1; }
port_up() { nc -z -w2 127.0.0.1 "$1" >/dev/null 2>&1; }

# ---------- Node ----------
resolve_node() {
  if [ -n "${NODE_BIN:-}" ] && [ -x "$NODE_BIN" ]; then NODE="$NODE_BIN"; return; fi
  local cand
  for cand in \
    "$HOME/.gradle/nodejs/node-v22.0.0-darwin-x64/bin/node" \
    "$(command -v node 2>/dev/null || true)" \
    /usr/local/bin/node; do
    [ -n "$cand" ] && [ -x "$cand" ] && { NODE="$cand"; return; }
  done
  fail "找不到 node（可用 NODE_BIN 指定）"
}
resolve_node
export PATH="$(dirname "$NODE"):$PATH"
log "node: $NODE ($($NODE -v))"

# ---------- 构建（按需） ----------
if [ "${E2E_BUILD:-0}" = "1" ] || [ ! -f "$ZIP" ] || [ ! -f "$H5JS" ]; then
  if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    for cand in "$HOME/Library/Java/JavaVirtualMachines/corretto-17"*/Contents/Home; do
      [ -x "$cand/bin/java" ] && { export JAVA_HOME="$cand"; break; }
    done
  fi
  if [ -z "${JAVA_HOME:-}" ]; then
    cand="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    [ -n "$cand" ] && export JAVA_HOME="$cand"
  fi
  [ -n "${JAVA_HOME:-}" ] || fail "构建需要 JDK 17（可设置 JAVA_HOME）"
  log "构建前端产物（JDK: $JAVA_HOME）"
  ( cd "$ROOT" && ./gradlew :demo:packLocalJsBundleDebug -Pkuikly.useLocalKsp=false --console=plain ) || fail "demo bundle 构建失败"
  ( cd "$ROOT" && ./gradlew :h5App:jsBrowserDevelopmentWebpack --console=plain ) || fail "h5App 构建失败"
else
  log "复用已有构建产物（E2E_BUILD=1 可强制重建）"
fi

# ---------- 静态目录（bundle 与壳同源，index.html 用相对路径引用 nativevue2.js） ----------
log "准备静态托管目录 $WORK"
rm -rf "$WORK" && mkdir -p "$WORK/shell"
unzip -oq "$ZIP" -d "$WORK/shell" || fail "解压 nativevue2.zip 失败"
cp "$H5JS" "$WORK/shell/h5App.js"
cp "$ROOT/h5App/src/jsMain/resources/index.html" "$WORK/shell/index.html"

# ---------- 服务（只起未监听的；nohup+</dev/null 彻底脱离本脚本 stdio） ----------
STARTED_GW=0; STARTED_SHELL=0
PID_GW=""; PID_SHELL=""
cleanup() {
  if [ "${E2E_KEEP:-0}" = "1" ]; then log "E2E_KEEP=1，保留服务（网关 :$GATEWAY_PORT，页面 :$SHELL_PORT）"; return; fi
  [ -n "$PID_SHELL" ] && kill "$PID_SHELL" 2>/dev/null
  [ -n "$PID_GW" ] && kill "$PID_GW" 2>/dev/null
  true
}
trap cleanup EXIT INT TERM

if port_up "$GATEWAY_PORT"; then
  log "网关已运行（:$GATEWAY_PORT），复用"
else
  log "启动网关（:$GATEWAY_PORT）"
  ( cd "$GW_DIR" && nohup "$NODE" server.js >"$WORK/gateway.log" 2>&1 </dev/null & echo $! >"$WORK/gateway.pid" ) 
  PID_GW="$(cat "$WORK/gateway.pid")"; STARTED_GW=1
fi

if port_up "$SHELL_PORT"; then
  log "页面服务已运行（:$SHELL_PORT），复用"
else
  log "启动页面服务（:$SHELL_PORT）"
  nohup python3 -m http.server "$SHELL_PORT" --bind 127.0.0.1 --directory "$WORK/shell" \
    >"$WORK/shell.log" 2>&1 </dev/null &
  PID_SHELL=$!; STARTED_SHELL=1
fi

# ---------- 等就绪 ----------
log "等待服务就绪"
ok=0
for _ in $(seq 1 40); do
  if curl -sf "http://127.0.0.1:$GATEWAY_PORT/health" >/dev/null \
    && curl -sf "http://127.0.0.1:$SHELL_PORT/" >/dev/null \
    && curl -sf "http://127.0.0.1:$SHELL_PORT/nativevue2.js" >/dev/null; then ok=1; break; fi
  sleep 0.5
done
[ "$ok" = "1" ] || fail "服务在 20s 内未就绪（看 $WORK/*.log）"
log "服务就绪"

# ---------- 跑测试（带硬超时） ----------
log "运行端到端测试（超时 ${TEST_TIMEOUT}s）"
cd "$GW_DIR"
GATEWAY_URL="http://127.0.0.1:$GATEWAY_PORT" WEB_URL="http://127.0.0.1:$SHELL_PORT" \
  "$NODE" test/sftp-web.test.js >"$WORK/test.log" 2>&1 &
TEST_PID=$!
elapsed=0
while kill -0 "$TEST_PID" 2>/dev/null; do
  if [ "$elapsed" -ge "$TEST_TIMEOUT" ]; then
    log "测试超时，强制结束"
    kill "$TEST_PID" 2>/dev/null
    break
  fi
  sleep 1; elapsed=$((elapsed + 1))
done
wait "$TEST_PID" 2>/dev/null; RC=$?
cat "$WORK/test.log"

if [ "$RC" = "0" ]; then log "全部通过"; else log "存在失败（exit=$RC），完整日志：$WORK/test.log"; fi
exit "$RC"
