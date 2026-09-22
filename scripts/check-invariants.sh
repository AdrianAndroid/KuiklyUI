#!/usr/bin/env bash
#
# 跨端不变量检查（CI 门禁）
#
# 用途：防止"悄悄引入平台分支 / 依赖倒置 / Electron 污染跨端"。
# 规则来源：devDocs/kuikly-app-development-plan.md §1.3 与 §6.8。
#
# 用法：bash scripts/check-invariants.sh
# 退出：0=全部通过；1=存在违规（打印 file:line）
#
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

FAIL=0
VIOLATIONS=""
WARNS=""
add() { VIOLATIONS="${VIOLATIONS}$1\n"; FAIL=1; }
warn() { WARNS="${WARNS}$1\n"; }

# 统一排除目录
EXCLUDES=(--exclude-dir=build --exclude-dir=.gradle --exclude-dir=node_modules \
  --exclude-dir=Pods --exclude-dir=.git --exclude-dir=.kilo --exclude-dir=worktrees \
  --exclude-dir=.kotlin --exclude-dir=.cxx)

scan() { # scan <args...>  -> 打印匹配行
  grep -rEn "${EXCLUDES[@]}" "$@" 2>/dev/null || true
}

section() { printf '\n[%s]\n' "$1"; }

# ---------------------------------------------------------------- I1
section "I1  commonMain 禁止依赖 core-render-*（平台源集引用仅告警）"
HITS="$(scan --include='*.kt' \
  '^[[:space:]]*import[[:space:]]+com\.tencent\.kuikly\.core\.render\.' \
  core/src/commonMain compose/src/commonMain)"
[ -n "$HITS" ] && add "$HITS"
# 平台源集（androidMain/iosMain）为 expect/actual 接线而引用 renderer 属既有例外：只提示
WARN_HITS="$(scan --include='*.kt' \
  '^[[:space:]]*import[[:space:]]+com\.tencent\.kuikly\.core\.render\.' \
  core/src/androidMain core/src/iosMain core/src/ohosArm64Main core/src/jsMain \
  compose/src/androidMain compose/src/iosMain compose/src/jsMain)"
[ -n "$WARN_HITS" ] && warn "I1(warn) 平台源集引用 renderer（既有例外，勿扩散）:\n$WARN_HITS"

# ---------------------------------------------------------------- I2
section "I2  commonMain 禁止平台 API（androidx.compose.runtime 除外）"
HITS="$(scan --include='*.kt' \
  '^[[:space:]]*import[[:space:]]+(android\.|androidx\.|platform\.UIKit|UIKit)' \
  core/src/commonMain demo/src/commonMain | grep -v 'androidx\.compose\.runtime' || true)"
[ -n "$HITS" ] && add "$HITS"

# ---------------------------------------------------------------- I2' (compose commonMain 仅允许 androidx.compose.runtime)
section "I2' compose/commonMain 仅允许 androidx.compose.runtime.*"
HITS="$(scan --include='*.kt' \
  '^[[:space:]]*import[[:space:]]+androidx\.compose\.' \
  compose/src/commonMain | grep -v 'androidx\.compose\.runtime' || true)"
[ -n "$HITS" ] && add "$HITS"

# ---------------------------------------------------------------- I8 (commonMain 不得出现平台分支)
section "I8  commonMain 不得出现 isElectron / isBrowser / isNode"
HITS="$(scan --include='*.kt' \
  'isElectron|Platform\.isElectron|isNodeJS' \
  core/src/commonMain compose/src/commonMain demo/src/commonMain)"
[ -n "$HITS" ] && add "$HITS"

# ---------------------------------------------------------------- I9 (electron 不得进入 Gradle 构建图)
section "I9  electron/ 不得被任何 Gradle 脚本引用"
HITS="$(scan --include='*.gradle.kts' --include='*.gradle' 'electron' .)"
[ -n "$HITS" ] && add "$HITS"
if [ -f "$ROOT/settings.gradle.kts" ] && grep -q "include.*electron" "$ROOT/settings.gradle.kts" 2>/dev/null; then
  add "settings.gradle.kts: include('electron')"
fi

# ---------------------------------------------------------------- I8' (electron 不得复制网关)
section "I8' electron/ 不得复制 sftp-gateway"
if [ -d "$ROOT/electron" ]; then
  for f in server.js sftp_gateway.js; do
    [ -f "$ROOT/electron/$f" ] && add "electron/$f: 疑似复制网关（应复用 ../sftp-gateway）"
  done
  if [ -d "$ROOT/electron/sftp-gateway" ]; then
    add "electron/sftp-gateway/: 疑似复制网关（应复用 ../sftp-gateway）"
  fi
fi

# ---------------------------------------------------------------- I4 (模块名常量一致性，弱校验)
section "I4  ModuleConst 与各端实现类名一致性（弱校验）"
for name in KRSftpModule KRSftpConnectionModule KRSftpFavoritesModule KRSftpPlaybackHistoryModule KRLocalMediaProxyModule; do
  grep -q "\"$name\"" core/src/commonMain/kotlin/com/tencent/kuikly/core/module/ModuleConst.kt 2>/dev/null \
    || add "ModuleConst.kt 缺少 $name"
done

# ---------------------------------------------------------------- 结果
printf '\n=== 不变量检查结果 ===\n'
if [ -n "$WARNS" ]; then
  echo "警告（不阻断）："
  printf '%b\n' "$WARNS" | sed '/^$/d' | head -20
fi
if [ "$FAIL" = "0" ]; then
  echo "PASS：全部不变量通过"
  exit 0
fi
echo "FAIL：发现违规（红线见 devDocs/kuikly-app-development-plan.md §1.3 / §6.8）"
printf '%b\n' "$VIOLATIONS" | sed '/^$/d' | head -80
exit 1
