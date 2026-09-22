# Kuikly 跨端 SFTP —— 自动化测试用例与执行规程

> **用途**：本文件是 SFTP 应用的**唯一测试规程**。每次改动（功能/安全/打包/依赖）后，
> 按 [§4 固定执行步骤](#4-固定执行步骤每次照跑) 依次执行；用例 ID 稳定，便于逐次对照回归。
>
> **关联**：`devDocs/kuikly-app-development-plan.md`（规划 §8 测试策略）、`AGENTS.md` §13（现状与踩坑）、
> `docs/SFTP-实现详解.md`（实现走读）。
>
> **原则**：用例可自动执行、结果可判定、失败可定位。**不支持的能力必须显式失败**（错误码 `9999`），
> 不得以"跳过/静默"掩盖。

---

## 目录

- [1. 测试分层](#1-测试分层)
- [2. 环境与前置](#2-环境与前置)
- [3. 用例清单（详细）](#3-用例清单详细)
  - [3.1 L0 不变量（静态门禁）](#31-l0-不变量静态门禁)
  - [3.2 L1 网关 RPC / 媒体（A 组）](#32-l1-网关-rpc--媒体a-组)
  - [3.3 L2 Web 端到端（B 组）](#33-l2-web-端到端b-组)
  - [3.4 L3 Electron 桌面壳（S 组）](#34-l3-electron-桌面壳s-组)
  - [3.5 L4 原生端集成（74 项）](#35-l4-原生端集成74-项)
  - [3.6 L5 构建与打包](#36-l5-构建与打包)
- [4. 固定执行步骤（每次照跑）](#4-固定执行步骤每次照跑)
- [5. 通过标准与判定规则](#5-通过标准与判定规则)
- [6. 失败排查指引](#6-失败排查指引)
- [7. 结果记录模板](#7-结果记录模板)
- [8. 维护规则](#8-维护规则)

---

## 1. 测试分层

| 层 | 名称 | 载体 | 是否需要真机/设备 | 典型耗时 |
|---|---|---|---|---|
| **L0** | 不变量静态门禁 | `scripts/check-invariants.sh` | 否 | <5s |
| **L1** | 网关 RPC / 媒体（A 组）| `sftp-gateway/test/sftp-web.test.js --rpc-only` | 否（需测试服务器）| ~5s |
| **L2** | Web 端到端（B 组，CDP 驱动 Chrome）| `sftp-gateway/test/sftp-web.test.js` | 否（需 Chrome）| ~60s |
| **L3** | Electron 桌面壳（S 组）| `electron/test/smoke.mjs` | 否 | ~60s |
| **L4** | 原生端集成（74 项）| `SftpIntegrationTestPage` | Android 模拟器 / iOS 模拟器 / macOS | ~5min/端 |
| **L5** | 构建与打包 | Gradle / Xcode / DevEco / electron-builder | 否 | 数分钟 |

**依赖顺序**：L0 → L1 → L2 → L3 →（L5）→ L4（按需）。

---

## 2. 环境与前置

### 2.1 工具链

| 工具 | 版本要求 | 说明 |
|---|---|---|
| JDK | **17**（corretto-17.0.13）| Gradle 7.6.3 不支持 JDK 25；不设 `JAVA_HOME` 会失败 |
| Node | ≥ 22 | 需要全局 `fetch` / `WebSocket`；本机可用 `~/.gradle/nodejs/node-v22.0.0-darwin-x64/bin` |
| Chrome | 任意近期版本 | CDP 驱动；路径可用 `CHROME_PATH` 覆盖 |
| Electron | 33.4.11（`electron/package.json` 固定）| 与本地 `@electron/get` 缓存对齐，避免重下 100MB |
| Python3 | 任意 | 仅用于静态托管测试页面 |
| Xcode / Android SDK / DevEco | 见 `AGENTS.md` §5.1 | L4/L5 按端需要 |

### 2.2 端口约定

| 端口 | 用途 |
|---|---|
| `18090` | 网关（默认；`SFTP_GATEWAY_PORT=0` 时由 OS 分配）|
| `8080` | 页面壳：`index.html` + `h5App.js` + `nativevue2.js`（**同源**；`index.html` 用相对路径引用，故三者必须同目录）|
| `9230`-`9333` | CDP 调试端口（Web 测试 / Electron 测试）|

> ⚠️ 历史文档里的 `8083`（单独托管 nativevue2.js）已**不再需要**：改为同源 8080 提供。

### 2.3 测试服务器与素材（内网低敏，`AGENTS.md` §13.6）

| 项 | 默认值 | 覆盖变量 |
|---|---|---|
| host / port | `192.168.2.2` / `22` | `SFTP_HOST` / `SFTP_PORT` |
| user / password | `zhaojian` / `zhaojian` | `SFTP_USER` / `SFTP_PASSWORD` |
| home | `/home/zhaojian` | `SFTP_HOME` |
| 媒体文件 | `${SFTP_HOME}/sftp_kuikly_media.mp4` | `SFTP_MEDIA` |
| 媒体大小 | `95627` 字节 | `SFTP_MEDIA_SIZE` |

**字节级校验基准**（证明传输未损坏）：

| 位置 | 期望（hex）| 变量 |
|---|---|---|
| offset 0（`ftyp` box 头）| `000000206674797069736f6d00000200` | `SFTP_MEDIA_HEAD16` |
| offset 50000（中段）| `bf83b361bd4b46fbbca64bc57df7c2ef` | `SFTP_MEDIA_MID16` |

### 2.4 其他可覆盖变量

| 变量 | 作用 |
|---|---|
| `GATEWAY_URL` | 网关地址（默认 `http://127.0.0.1:18090`）|
| `WEB_URL` | 页面地址（默认 `http://127.0.0.1:8080`）|
| `CHROME_PATH` | Chrome 可执行文件路径 |
| `HEADLESS` | `0` = 有头（可见浏览器，便于观察）；默认无头 |
| `SLOWMO` | 每步额外等待毫秒（有头观察用，如 `200`）|
| `E2E_BUILD` / `E2E_KEEP` / `E2E_TEST_TIMEOUT` | `npm run e2e` 编排开关（见 §4）|

---

## 3. 用例清单（详细）

> 判定口径：**PASS 必须来自真实断言**；不允许"拿到异常值也算过"（如早期 S9 的教训）。

### 3.1 L0 不变量（静态门禁）

**命令**：`bash scripts/check-invariants.sh`　**通过标准**：退出码 `0`（警告不阻断）

| ID | 检查内容 | 预期 |
|---|---|---|
| I1 | `core/commonMain`、`compose/commonMain` 不得 `import com.tencent.kuikly.core.render.*` | 无匹配 |
| I1(warn) | 平台源集（androidMain/iosMain…）引用 renderer | 仅告警（既有例外，勿扩散）|
| I2 | `core/commonMain`、`demo/commonMain` 不得 import `android.*`/`androidx.*`/`UIKit`（`androidx.compose.runtime` 除外）| 无匹配 |
| I2' | `compose/commonMain` 仅允许 `androidx.compose.runtime.*` | 无其它 androidx |
| I8 | commonMain 不得出现 `isElectron` / `isBrowser` / `isNodeJS` | 无匹配 |
| I9 | `electron/` 不得被任何 Gradle 脚本引用；`settings.gradle.kts` 不得 include | 无匹配 |
| I8' | `electron/` 不得复制 `sftp-gateway`（存在 `electron/server.js` 即违规）| 无该文件 |
| I4 | `ModuleConst.kt` 中 5 个 SFTP 模块名齐全 | 全部存在 |

---

### 3.2 L1 网关 RPC / 媒体（A 组）

**命令**：`cd sftp-gateway && npm run test:rpc`　**前置**：网关已启动（§4 步骤 2）
**通过标准**：`=== 结果: 14/14 通过 ===`

| ID | 目的 | 步骤（自动化）| 预期结果 |
|---|---|---|---|
| **A1** | 网关存活 | `GET /health` | `{ok:true, port:<实际端口>}` |
| **A2** | 连接真实服务器 | `sftp.connect{host,port,user,password}` | 返回 `sessionId` |
| **A3** | 列远端目录 | `sftp.list{remotePath=HOME}` | 返回条目 > 0 |
| **A4** | stat 媒体文件 | `sftp.stat{remotePath=MEDIA}` | `entry.size == SFTP_MEDIA_SIZE` |
| **A5** | 随机读（头部）| `openRead` + `read{offset:0,length:16}` | 解码 hex == `HEAD16` |
| **A6** | 随机读（中段）| `read{offset:50000,length:16}` | 解码 hex == `MID16` |
| **A7** | 媒体 HTTP Range | `POST mediaProxy.registerToken` 后 `GET /<token>/<name>`，`Range: bytes=0-15` | `206` + `Content-Range: bytes 0-15/<size>` |
| **A8** | Range 回包字节 | 同 A7 的响应体 | hex == `HEAD16` |
| **A9** | 建目录 + stat | `mkdir{recursive:true}` → `stat` | `entry.isDir == true` |
| **A10** | 重命名 | `rename{oldPath,newPath}` → `stat(newPath)` | 新路径存在且 `isDir` |
| **A11** | 递归删除 | `rm{recursive:true}` → `stat` | 返回 `error`（已不存在）|
| **A12** | 连接持久化 | `connection.add` → `list` 含该 id → `remove` | 列表包含该 id |
| **A13a** | **主机指纹 TOFU** | `knownHosts.list` 查 `host:port` | 存在且形如 `SHA256:...` |
| **A13b** | **指纹不匹配阻断** | 篡改 `data/sftp_known_hosts.json` 为 `SHA256:WRONG` 后 `sftp.connect` | 返回 `error` 且 `code == 1004` |

> A13b 结束会**还原** known_hosts 文件；幂等可重复执行。

---

### 3.3 L2 Web 端到端（B 组）

**命令**：`cd sftp-gateway && npm test`（无头）／`HEADLESS=0 SLOWMO=200 npm test`（有头）
**前置**：网关 + 页面（8080 同源）已就绪
**通过标准**：`=== 结果: 21/21 通过 ===`（无头时 B6b 记 SKIP 视为通过）

| ID | 目的 | 步骤（自动化）| 预期结果 |
|---|---|---|---|
| **B1** | 首页渲染 | 打开 `?page_name=SftpHomePage`，轮询正文 | 含「SFTP 客户端」|
| **B2** | 首页无异常 | 收集 `Runtime.exceptionThrown` | 0 条 |
| **B3** | 浏览真实目录 | 打开 `?page_name=SftpBrowserPage&host&user&password&remotePath`，轮询 | 正文含媒体文件名 |
| **B4** | 浏览页无异常 | 同上收集 | 0 条 |
| **B5** | 播放时间前进 | 打开播放页；若未自动起播按一次 `K` | 当前时间码递增且总时长 ≥ `00:05` |
| **B6a** | **键盘 seek** | `←` 归零 → `→` 跳转 | 时间码增大（或到片尾）|
| **B6b** | **拖动 seek**（仅 `HEADLESS=0`）| 定位进度条（高≈28 的最宽元素）→ 按下 90% 拖到 20% → 松开 | 时间码 ≤ `00:03`；**无头下记 SKIP** |
| **B7** | 设置菜单（倍速）| 鼠标唤醒控制条 → 点击 `1.0×` | 出现 `0.5×` 与 `1.25×` 选项 |
| **B8** | 播放页无异常 | 收集 | 0 条 |
| **B11** | 首页收藏条目可点击进入 | 收藏 Tab → 点条目 | 跳转意图为 `SftpBrowserPage`。**CDP 无法触发 Kuikly Scroller 内条目点击**（既有连接条目同样无法触发）→ 自动跑记 `SKIP`，需人工验证（见下）|
| **B12** | 首页历史条目可点击进入 | 历史 Tab → 点条目 | 跳转意图为 `SftpPlayerPage`。同 B11，自动记 `SKIP` |
| **B13** | 返回按钮优先回到上一级 | 进入 `HOME/kr_webtest_nav` → 点 `<` | 路径文本变为 `HOME`（不再含 `kr_webtest_nav`）|
| **B14** | 文件列表按名称升序 | 在含 `b_zz/a_aa/c_mm` 的目录 | 渲染顺序为 `a_aa, b_zz, c_mm` |
| **B15a** | 选集弹层可打开且**不全屏** | 播放页点 `☰` | 出现 `✕`；标题/关闭键位于屏幕下半部（`top > 0.4*innerHeight`）|
| **B15b** | 选集弹层可关闭（`✕` / 点遮罩）| 打开后点 `✕`，或点遮罩区域 | 弹层消失。CDP 合成事件在该弹层不可靠 → 自动记 `SKIP`，需人工验证 |
| **B16** | 全屏下存在返回按钮 | 点 `⛶` 后 | 存在 `<` 返回元素（全屏下导航栏隐藏的补偿入口）|
| **B17** | 播放页无异常（上述交互后）| 收集 | 0 条 |

**需人工验证的用例（自动记 SKIP，发布前必须有记录）**：

| ID | 人工步骤 | 期望 |
|---|---|---|
| B11 | 首页 → 收藏 Tab → 点击条目 | 打开浏览页（目录）或播放页（文件）|
| B12 | 首页 → 历史 Tab → 点击条目 | 打开播放页 |
| B15b | 播放页 → `☰` 打开选集 → ①点弹层外的暗色区域 ②点 `✕` | 两种方式都能关闭（不再"只能选完才消失"）|

> 说明：这三条依赖"点击 Kuikly `Scroller` 内条目"，CDP 合成事件在该环境不可靠（**既有可用的连接条目同样无法触发**，可复现），故不纳入自动断言，改为人工；一旦找到可靠驱动手段应恢复自动断言。

**已知测试差异（必须知悉）**：CDP 合成的 `pan` move 在**无头**下不稳定，故拖动只在有头断言；
无头由 B6a（键盘 seek）覆盖 seek 能力。每次运行使用**独立 Chrome profile**（避免复用旧实例白屏）。

---

### 3.4 L3 Electron 桌面壳（S 组）

**命令**：
```bash
cd electron
npm test                                                   # 开发态
ELECTRON_TEST_BIN="dist/mac/Kuikly SFTP.app/Contents/MacOS/Kuikly SFTP" node test/smoke.mjs   # 打包版
```
**前置**：`npm install`；`npm run sync`（或 `npm run build:web` 后同步）
**通过标准**：`=== smoke: 10/10 通过 ===`

| ID | 目的 | 步骤 | 预期结果 |
|---|---|---|---|
| **S1** | 应用启动 + CDP | 启动应用并等待 `9333/json/version` | 可用 |
| **S2** | 渲染进程页面 | 轮询 `/json/list` 找 `type=page` | 命中 `index.html` |
| **S3** | 首页渲染 | 轮询正文（36MB bundle 冷启动较慢）| 含「SFTP 客户端」|
| **S4** | 注入网关地址 | 读 `window.__SFTP_GATEWAY_URL__` | 形如 `http://127.0.0.1:<port>` |
| **S5** | 无 JS 异常 | 收集异常/日志 | 0 异常 |
| **S6** | 渲染进程可达网关 | 页面内 `fetch(gw + '/health')`（`awaitPromise`）| `{ok:true}`（验证 `file://` 跨域可行）|
| **S7** | 网关连真实服务器 | Node 侧 `sftp.connect` | 返回 `sessionId` |
| **S8** | 桌面壳内真实目录 | 导航到浏览页 → 轮询正文 | 含媒体文件名 |
| **S9** | 桌面壳内播放 | 导航到播放页 → 轮询真实时间码 → 等待 | **必须拿到 `mm:ss` 且递增**（如 `00:00 → 00:01`）|
| **S10** | 验证后无异常 | 收集 | 0 异常 |

---

### 3.5 L4 原生端集成（74 项）

**载体**：`demo/.../pages/sftp/SftpIntegrationTestPage.kt`（逐条断言，日志 tag `SftpTest`）

| 端 | 运行方式 | 通过标准 |
|---|---|---|
| Android | `adb shell am start ... --es pageName SftpIntegrationTestPage --es pageData '{...}'`；`adb logcat -d \| grep '\[KLog\]\[SftpTest\]'` | `SUITE END … fail=0`（目标 74/74）|
| iOS | 临时把 `iosApp/…/ContentView.swift` 指向该页并注入参数；`xcrun simctl spawn … log show` 取 `SftpTest` | 74/74 |
| macOS | 同 iOS（`macApp/…/ContentView.swift`）；日志 `diagnostics.log` | 74/74 + 界面操控（播放/seek/暂停恢复）|

**覆盖分组**（74 项的构成）：
1. 连接/断开（含错误路径）
2. 列目录 / stat / 符号链接
3. 随机读（含 offset 0/中段/EOF/越界）
4. 上传 / 下载（含字节校验）
5. mkdir / rm（递归）/ rename / move / copy
6. chmod / chown / setMtime
7. batchTask（DELETE/MOVE/COPY/DOWNLOAD）
8. 收藏 / 播放历史 / 连接持久化
9. 本地代理端口与 token

**真机/模拟器要求**：Android 模拟器（Pixel_8a_API_35 / Android 15）；iOS 模拟器；macOS 本机。
**跑完必须改回** `SftpHomePage`（避免污染默认入口）。

---

### 3.6 L5 构建与打包

| ID | 内容 | 命令 | 预期 |
|---|---|---|---|
| **L5-1** | 不变量 | `bash scripts/check-invariants.sh` | `PASS` |
| **L5-2** | Web 产物 | `./gradlew :demo:packLocalJsBundleDebug -Pkuikly.useLocalKsp=false` + `:h5App:jsBrowserDevelopmentWebpack` | `BUILD SUCCESSFUL` |
| **L5-3** | Android 渲染器 | `./gradlew :core-render-android:compileDebugKotlin` | `BUILD SUCCESSFUL` |
| **L5-4** | macOS/iOS | `xcodebuild … build`（需 `JAVA_HOME=JDK17`）| `BUILD SUCCEEDED` |
| **L5-5** | HarmonyOS | `./2.0_ohos_demo_build.sh` / CMake | 产物生成 |
| **L5-6** | Electron 依赖 | `cd electron && npm install` | `node_modules/electron/path.txt` 存在 |
| **L5-7** | Electron 打包 | `cd electron && npm run dist` | 产出 `dist/Kuikly SFTP-<ver>.dmg`（含 `Resources/gateway`）|
| **L5-8** | 打包版功能 | `ELECTRON_TEST_BIN=… node test/smoke.mjs` | 10/10 |
| **L5-9** | 版本一致性 | 各端产物含同一版本号 | 一致 |

---

## 4. 固定执行步骤（每次照跑）

> **每次改动后按此顺序执行**。可一键：`bash scripts/run-all-tests.sh`（见 §4 末尾），
> 也可手工逐条执行。

```bash
# ---------- 0. 环境 ----------
export JAVA_HOME=/Users/<你>/Library/Java/JavaVirtualMachines/corretto-17.0.13/Contents/Home
export PATH="$HOME/.gradle/nodejs/node-v22.0.0-darwin-x64/bin:$PATH"   # Node ≥22
cd <repo>

# ---------- 1. L0 不变量（必过）----------
bash scripts/check-invariants.sh

# ---------- 2. 起网关（若未运行）----------
cd sftp-gateway
[ -f node_modules/ssh2/package.json ] || npm install
(nc -z -w2 127.0.0.1 18090 && echo "gateway UP") || (node server.js >/tmp/gw.log 2>&1 &)
for i in $(seq 1 20); do curl -sf http://127.0.0.1:18090/health >/dev/null && break; sleep 0.5; done
curl -s http://127.0.0.1:18090/health   # 期望 {"ok":true,...}

# ---------- 3. L1 网关 RPC（必过 14/14）----------
npm run test:rpc

# ---------- 4. L2 Web E2E ----------
# 4a. 起页面服务（8080 同源提供 index.html + h5App.js + nativevue2.js）
#     最省事：用编排脚本（会自动构建+起服务+测试+清理）
E2E_KEEP=1 npm run e2e          # 首次或产物变更后建议 E2E_BUILD=1
# 4b. 若服务已在跑，直接：
npm test                        # 无头：21/21（B6b SKIP）
HEADLESS=0 SLOWMO=200 npm test  # 有头：21/21（含拖动 seek）

# ---------- 5. L3 Electron ----------
cd ../electron
[ -f node_modules/electron/path.txt ] || npm install
npm run sync
npm test                                   # 开发态 10/10
npm run dist                               # 打包（产出 dmg）
ELECTRON_TEST_BIN="dist/mac/Kuikly SFTP.app/Contents/MacOS/Kuikly SFTP" node test/smoke.mjs  # 打包版 10/10

# ---------- 6. L5 关键构建（按改动范围选跑）----------
cd ..
./gradlew :core-render-android:compileDebugKotlin   # 改 Android 时
# xcodebuild（改 iOS/macOS 时，需 JAVA_HOME=JDK17）

# ---------- 7. L4 原生集成（按改动范围/里程碑跑）----------
# 见 §3.5（Android 模拟器 / iOS 模拟器 / macOS）

# ---------- 8. 归档结果 ----------
# 按 §7 模板记录本轮结果（用例数、失败项、证据、commit）
```

**一键脚本（可选）**：`scripts/run-all-tests.sh` 顺序执行 1→5（不包含需设备的 L4），
任一步失败即中止并打印最后 20 行日志。用法：

```bash
bash scripts/run-all-tests.sh            # L0 + L1 + L2(自动编排) + L3
SKIP_ELECTRON=1 bash scripts/run-all-tests.sh
```

---

## 5. 通过标准与判定规则

### 5.1 必过项（任一失败即阻断）

| 层 | 必过标准 |
|---|---|
| L0 | 退出码 0 |
| L1 | `14/14` |
| L2（无头）| `21/21`（B6b 计 SKIP）|
| L2（有头，发布前跑一次）| `21/21`（含 B6b）|
| L3（开发态）| `10/10` |
| L3（打包版，发布前）| `10/10` |
| L5-2/L5-3 | `BUILD SUCCESSFUL`（改动涉及则必过）|

### 5.2 允许跳过（需说明）

| 项 | 条件 |
|---|---|
| B6b 拖动 seek | 无头环境（由 B6a 覆盖）；发布前用 `HEADLESS=0` 补跑 |
| L4 某端 | 无设备/模拟器；**必须在结果记录中标注"未验证"**，且不得在文档中宣称可用 |
| L5-4/L5-5 | 未改动该端 |

### 5.3 判定纪律

1. **不接受"空过"**：断言必须验证真实值（时间码必须匹配 `mm:ss` 且递增）。
2. **不接受"静默跳过"**：跳过必须打 `SKIP` 并写明原因。
3. **失败即记录**：在 §7 记录失败 ID、原始输出、定位结论与修复 commit。
4. **与文档一致**：若某端未验证，`AGENTS.md` §13 能力矩阵不得标"可用"。

---

## 6. 失败排查指引

| 症状 | 可能原因 | 动作 |
|---|---|---|
| A1 `fetch failed` | 网关未启动/端口占用 | 重启网关；确认 18090 未被旧进程占用（`lsof -ti tcp:18090`）|
| A2 连接失败 | 测试机不可达 / 凭据变更 | `ssh` 手动连通验证；检查 `SFTP_*` 变量 |
| A13b 未阻断 | known_hosts 未生效/被并发改写 | 确认 `data/sftp_known_hosts.json` 可写；重跑（脚本会还原）|
| B1/B3 正文为空 | 页面服务未起 / bundle 未部署 / 冷启动慢 | 确认 8080 同源提供三个文件；等待轮询（文档已内置 30-40s）|
| B2/B4/B8 有 Uncaught | 页面 JS 异常 | 看 `Runtime.exceptionThrown` 与控制台；多为网关不可达或 session 失效 |
| B5 时间不动 | 自动播放被拦 | 测试已内置按 `K` 兜底；如仍不动，检查 `playControl` |
| B6b 失败（有头）| 进度条定位不到 / 视频已播完 | 先 `←` 归零再拖；确认进度条高≈28 |
| B6b 在无头失败 | CDP 合成 pan 不稳（已知）| 用 `HEADLESS=0`；无头看 B6a |
| B7 菜单不展开 | 控制条被自动隐藏 | 测试已 `wake()`（mousemove）；确认 `mousemove` 事件到达 |
| S1/S2 失败 | `ELECTRON_RUN_AS_NODE=1` | 用 `env -u ELECTRON_RUN_AS_NODE`（`npm test` 已内置剔除）|
| S3 正文为空 | 窗口未加载完 | 测试已轮询 40s；确认 `resources/` 已 `npm run sync` |
| S6 `[object Object]` | 忘了 `awaitPromise` | 测试已修；如自写脚本注意加 `awaitPromise:true` |
| 打包版 S7/S8 失败 | 包内未带网关 | 确认 `dist/.../Resources/gateway/server.js` 存在（`extraResources`）|
| 页面白屏（浏览器）| Chrome profile 复用 | 测试已用独立 profile；手工排查时换新 profile |
| 离线环境 CDN 报错 | `libpag` 走 jsdelivr | 不影响功能；忽略或本地化 |
| Gradle 报 ICE / 版本错 | 用了 JDK 25 | 设 `JAVA_HOME` 到 JDK 17 |

---

## 7. 结果记录模板

> 每次执行后复制下表填写，附于 PR / 提交信息或进度文档。

```markdown
### 测试记录 <YYYY-MM-DD> <commit>

| 层 | 命令 | 结果 | 备注 |
|---|---|---|---|
| L0 不变量 | `bash scripts/check-invariants.sh` | PASS | 1 条既有警告 |
| L1 网关 RPC | `npm run test:rpc` | 14/14 | |
| L2 Web（无头）| `npm test` | 21/21（B6b SKIP）| |
| L2 Web（有头）| `HEADLESS=0 SLOWMO=200 npm test` | 21/21 | 拖动 seek = 00:01 |
| L3 Electron（开发）| `npm test`（electron）| 10/10 | |
| L3 Electron（打包）| `ELECTRON_TEST_BIN=… node test/smoke.mjs` | 10/10 | dmg 112MB |
| L5 构建 | `:core-render-android:compileDebugKotlin` | SUCCESS | |
| L4 Android | 集成自测页 | 74/74 | 模拟器 Pixel_8a_API_35 |
| L4 iOS | 集成自测页 | 74/74 | 模拟器 |
| L4 OHOS | — | 未验证 | 无设备 |

失败项：无
证据：`/tmp/e2e_*.log`、`dist/Kuikly SFTP-<ver>.dmg`
```

---

## 8. 维护规则

1. **新增/修改能力必须同步用例**：新增 SFTP 方法 → 至少补 1 条 A 组；新增页面/交互 → 补 B 组；
   新增桌面能力 → 补 S 组；新增端能力 → 在集成自测页加断言并在 §3.5 登记。
2. **用例 ID 不复用**：废弃用例标注 `DEPRECATED` 而非改号，保持回归可对照。
3. **本文与以下保持一致**：
   - `AGENTS.md` §13（能力矩阵与踩坑）、§11（专题文档登记）
   - `devDocs/kuikly-app-development-plan.md` §8（测试策略）、里程碑 DoD
   - `devDocs/sftp-development-progress.md`（每轮测试结果）
4. **测试不通过不得发布**；允许跳过的项必须在 §7 明确标注并说明理由。
5. 若用例与实际实现长期不一致（如 UI 重构导致定位失效），**先改用例再改断言**，禁止直接删除断言。

---

> 本规程为测试基线：**改动 → 按 §4 执行 → §7 记录 → §5 判定**。
> 任何"绕过测试"的合并都视为违反 `AGENTS.md` §13.3 的跨端一致性硬规则。
