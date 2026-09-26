# SFTP 跨平台客户端 — 开发进度总览

> 本文件是 SFTP 客户端专题的**当前进度快照**，配合以下文档一起看：
> - `AGENTS.md` §13 — 专题压缩上下文（必读）
> - `docs/SFTP-Client.md` — 3590+ 行完整设计文档（§23 跨平台架构）
> - `docs/SFTP-实现详解.md` — 学习/实现向完整走读
> - `devDocs/sftp-impl-plan.md` — 全平台 Phase 0~6 落地清单
> - `devDocs/sftp-player-modernz-plan.md` — 播放页 mpv OSC 改造方案
>
> 最近一次更新：2026-09-25（`524a2ebb`，含 §0 最新快照）

---

## 0. 最新状态快照（2026-09-25）—— 下一个模型从这里开始

> 桌面端（Electron）架构全貌单独成篇：**`devDocs/kuikly-electron-architecture.md`**（进程/窗口模型、启动加载链路、两个网关、preload 暴露面、宿主能力桥、独立窗口、构建打包、测试体系、踩坑）。**别只盯 KMP 六端而漏掉 Electron。**

### 0.1 仓库 / 环境 / 命令

- 仓库：`/Users/zhaojian/bin/macmini/KuiklyUI`，分支 **`zhaojian`**，远程 `git@github.com:AdrianAndroid/KuiklyUI.git`（推送 `git push origin zhaojian`）。
- JDK 17（**JDK 25 会破坏 Gradle 7.6.3**）：`export JAVA_HOME=/Users/zhaojian/Library/Java/JavaVirtualMachines/corretto-17.0.13/Contents/Home`。
- Node（Gradle 内置）：`export PATH="$HOME/.gradle/nodejs/node-v22.0.0-darwin-x64/bin:$PATH"`。
- 常用 Gradle 参数：`-Pkuikly.useLocalKsp=false --offline`；Android 编译需 `ANDROID_HOME=$HOME/Library/Android/sdk`。
- **启动 Electron 必须去掉 `ELECTRON_RUN_AS_NODE`**（VS Code/Kilo 会泄漏，导致主进程 `exit(1)`）：
  `env -u ELECTRON_RUN_AS_NODE open "/Applications/Kuikly SFTP.app"`。
- 测试用外部网关（远程夹具/直连 SFTP）：`cd electron && npm run gateway`（端口由 `electron/test/env.mjs` 按实例计算：
  主 clone=18090，linked worktree=18090+slot*100）。
  页面业务走的是 **Electron 自带网关（随机端口）**，所以收藏/历史断言要用页面内 `window.__SFTP_GATEWAY_URL__`，不能走固定端口。
- **并行 worktree**：CDP 端口/userData/本地文件根/远端夹具名一律由 `electron/test/env.mjs` 按 `KR_INSTANCE` 隔离；
  清理用 `node electron/scripts/pretest-kill.js`（只清本实例）。详见 `AGENTS.md §3.1 规则 13`。

### 0.2 交付流程（固定，见 `AGENTS.md §14.1`）

`npm run dist:release`（**已修**：会先 `build:web:release` 再 sync+打包；此前只 sync，release 产物陈旧 → 新代码打不进包）
→ 优雅退出旧实例 + `ditto` 覆盖安装到 `/Applications` + `xattr -dr com.apple.quarantine`
→ `env -u ELECTRON_RUN_AS_NODE open` 启动 → 提交 → `git push origin zhaojian`。

### 0.3 本轮（2026-09-25）新增 / 修复

| 项 | 说明 | 证据 |
|---|---|---|
| **终端改用 xterm.js** | Web/桌面主路径用 vendored `xterm.js`（MIT）做仿真；native 回退共享网格 `TerminalBuffer`/`TerminalGridView`。**真实回车曾完全无效**：命令 `Input` 未注册 `inputReturn`（Web 端 Enter keydown 未绑定）→ 已补。去掉状态栏 `#轮询计数` 残留 | `test:term` **7/7**（真实输入 + 回车 `echo TERM_$((6*7))` → `TERM_42`、`whoami`） |
| **本地 pty 就绪** | 启动期写入会把 ZLE 带偏（回显但不执行）→ 就绪判定「安静 900ms / 硬上限 5s」，期间输入排队 | 同上 |
| **目录 / 单文件缓存** | `cache/CacheManager.kt`（全局单例 + 顺序状态机）+ `CacheListOverlay.kt`（**页内浮层**，不新增路由页）。目录递归、**单文件也支持**、超阈值先确认、暂停/继续/取消/清空已完成 | `features` **F14–F18/F22/F23** |
| **设置页 + 更多入口** | 终端历史条数（5/10/20/50）、播放历史条数（5/10/20/50/200/1000）、清空缓存/播放历史、关于；首页右下「更多」抽屉 | `features` **F10/F11/F12/F21** |
| **首页「⚙ 设置」直达** | 右下角 FAB 在桌面端可能落在可视区外 → 顶部新增 ⚙ 入口 | `features` **F24** |
| **复制路径按钮** | 浏览页标题旁 `⧉` → 复制 `host:path`（Web/桌面；`supportsClipboard()` 门控） | `features` **F25**（剪贴板内容已校验） |
| **窗口 resize 修复** | SPA 模式 `handleEntry()` 提前 return，resize 监听装在 return 之后 → 永不生效；core 仅带 densityInfo 才重排。修复：监听移入 `installHostEventBridges()` + `KuiklyRouter.updateRootViewSizeForActive()` + core 尺寸变化即 `markDirty/layoutIfNeed` | `smoke` **S9l 通过**（`video 宽 1180→760→1180`）；安装版实测根内容宽同步 |
| **打包首帧空白** | 打包态 `#root` 高 0（layout 未触发）→ 窗口空白。`main.js` 加载完成后派发 `resize` nudge | 安装版 CDP：`本地文件管理/历史/⚙` 均渲染 |
| **测试防卡死** | `waitFor`/CDP `ev`/`rpc`/页面内 `fetch` 全部加超时（曾因页面 fetch 无超时 + `waitFor` 无超时 → 整体卡死）；看门狗内 `SIGKILL` 子进程；逐条打印耗时 | 规则写入 `AGENTS.md §3.1 规则 7` |
| **toast 不拦截点击** | `h5App/utils/Ui.kt` toast `pointer-events:none`（否则盖住终端/缓存悬浮条） | — |

### 0.4 测试套件与当前通过数（2026-09-25）

| 套件 | 命令 | 结果 |
|---|---|---|
| 功能（收藏/历史/设置/终端历史/缓存/复制路径） | `cd electron && npm run test:features` | **25/25** |
| 终端 | `npm run test:term` | **7/7** |
| 冒烟（首帧/播放/seek/切集/续播/resize） | `npm test` | **22/22**（新增 S3b 首帧非空白；S9l 由 SKIP 转硬断言；S9k 阈值改语义断言） |
| 独立播放窗口 | `npm run test:player` | **8/8** |
| 文本/Markdown 查看器 | `npm run test:text` | **16/16** |
| 双栏文件管理器 | `npm run test:dual` | **28/28** |
| `core/file-manager` 单测 | `./gradlew :core:file-manager:jvmTest :core:file-manager:jsNodeTest` | BUILD SUCCESSFUL |
| 集成自测页（原生端） | `SftpIntegrationTestPage` | macOS/iOS/Android 74/74（历史结论） |

### 0.5 已知问题 / 待办（下一个模型优先看）

1. **选集切换播放**：安装版用 `/data` 真实视频复测**可切换可播放**（播放中/暂停中切集均自动起播，无 `MEDIA_ERR_DECODE`），未能复现"切集放不了"；等用户用 **`⧉` 复制路径**反馈具体文件再定位。
2. **HarmonyOS 播放未实现**：`KRLocalHttpProxy` 仍是桩、无视频组件 → OHOS 暂不支持播放（其余 SFTP 能力已实现，运行时未验证）。
3. **小程序未验证**：复用 Web JS 模块，需网关可达 + 微信 request 域名白名单。
4. **播放异常信息**：页面目前只有「加载中/文件不存在」文案，遇到黑屏/解码失败时用户不易反馈；可考虑把 `<video>.error.code` 显示出来。
5. **native 端缓存/剪贴板/终端**：`cacheRoot()/supportsClipboard()/supportsTerminal()` 在 native 返回 false → 入口隐藏（未伪报）；接入点见 §13.1.5/13.1.6。
6. **网关安全**：known_hosts 校验在 Android(TOFU)+Web 已接，iOS/OHOS 待补；Web 网关会话在内存，重启即失效。

### 0.6 关键文件（本轮相关）
- 终端：`demo/.../sftp/terminal/SftpTerminalPage.kt`、`TerminalModule.kt`、`TerminalBuffer.kt`、`TerminalGridView.kt`；
  `h5App/src/jsMain/resources/lib/xterm.js` + `kr-terminal.js`；`demo/.../base/BridgeModule.kt`（xtermMount/Write/Resize/Dispose/SetVisible）。
- 缓存：`demo/.../sftp/cache/CacheManager.kt`、`CacheListOverlay.kt`、`CacheEngine.kt`（纯逻辑）；浏览页入口 `SftpBrowserPage.kt`。
- 设置/更多/复制路径：`SftpSettingsPage.kt`、`SftpHomePage.kt`（更多抽屉 + ⚙ + 连接/历史）、`SftpBrowserPage.kt`（`⧉`）。
- resize：`h5App/src/jsMain/kotlin/Main.kt`、`manager/KuiklyRouter.kt`、`core/.../pager/Pager.kt`；`electron/main.js`（首帧 nudge）。
- 测试：`electron/test/features.mjs`（F1–F25）、`terminal.mjs`、`smoke.mjs`、`dual-pane.mjs`、`player-window.mjs`、`text-viewer.mjs`。
- 截图证据：`electron/test/artifacts/`。
- **桌面端（Electron）架构**：`devDocs/kuikly-electron-architecture.md`；实现 `electron/`（`main.js`/`preload.js`/`electron-builder.yml`/`scripts/`/`test/`）
  + 网关 `sftp-gateway/` + 宿主桥 `h5App/src/jsMain/kotlin/module/KRBridgeModule.kt`。

### 0.7 踩坑速查（勿回退）
- **响应式**：状态必须 provider + 在 `attr{}`/`vif` 条件 lambda 内读取；结构层 `if/when` 只算首帧（设置项文案曾因此不刷新）。
- **浮层**必须放在内容区之后且 `positionAbsolute()`；**快照/列表用页内浮层**，不要为页内状态新增路由页（宿主对新增页名解析在 Web 下曾 `PagerNotFoundException`）。
- **不要用 kuikly core 的 `GlobalScope.launch + delay`** 做续跑/节流（依赖 `currentPageId`，异步回调里可能不恢复）；用 kotlinx.coroutines 或页面 `setTimeout`。
- **Web toast 必须 `pointer-events:none`**；**行内小按钮要独立格**，否则与父行点击冲突。
- **网关绝对路径写入**要向上找最近存在祖先目录做校验（否则新建多级目录被 `LOCAL_PATH_DENIED` 误拒）。
- **CDP 合成按键会触发 macOS「听写」**：测试用 `Input.insertText` + DOM `KeyboardEvent`，不要用 `Input.dispatchKeyEvent`。
- **Kotlin/JS 禁正则**解析 Markdown（unicode 模式抛 `Lone quantifier brackets`）。

### 0.8 跨端功能对齐（2026-09-25，以 Electron 为基准）

目标：Electron 已实现的能力在各端同步（编译通过即可；Electron 跑用例）。

| 能力 | Web/Electron | Android | iOS | macOS | MiniApp | OHOS |
|---|---|---|---|---|---|---|
| 远程终端 | ✅ xterm + 网关 shell | ✅ JSch `ChannelShell`（`KRTerminalModule`） | ✅ NMSSH shell（`KRTerminalModule`，resize no-op） | ✅ 同 iOS | ❌ 显式不支持 | ✅ libssh2 shell（C++，单开独立 SSH 连接 + pty） |
| 本地终端 | ✅ 网关本地 pty | ❌ 无本地 shell（显式失败） | ❌ | ❌ | ❌ | ❌ |
| 剪贴板复制路径 | ✅ | ✅ ClipboardManager | ✅ UIPasteboard | ✅ NSPasteboard | ✅ wx.setClipboardData | ✅ pasteboard（ETS） |
| 目录/单文件缓存 | ✅ 网关直写 | ✅ 沙盒 `.kuikly_cache`（download 支持绝对路径） | ✅ Caches/.kuikly_cache | ✅ 同 iOS | ❌ 无宿主目录 | ✅ cacheDir/.kuikly_cache（ETS） |
| 清空缓存 | ✅ | ✅ | ✅ | ✅ | ❌ | ✅（ETS） |
| 独立窗口播放 | ✅ | ❌ 回退页内 | ❌ 回退页内 | ❌ 回退页内 | ❌ | ❌ |
| 双栏文件管理 | ✅（commonMain 页 + `lfXxx`） | ✅ 沙盒 `filesDir/local` | ✅ `Documents/local` | ✅ 同 iOS | ❌ 无本地 FS | ❌ 待 ETS 本地 FS |

本轮改动与验证（**原生端仅编译，不跑原生用例**）：

- **修掉一个真实跨端阻断**：`SftpSettingsPage`（commonMain）里用了 `js()`/`dynamic` → Android/iOS/macOS 根本编不过。
  已把「清空缓存」改为 `BridgeModule.clearCache()` 宿主能力，各端实现（Web 删 `<home>/.kuikly_cache`；Android/iOS/macOS 删沙盒缓存目录）。
- `SftpHomePage`：远程终端入口不再限定 `isWebLike`（改由 `supportsTerminal()` 决定）；本地终端入口限定 Web/桌面（原生无本地 shell）。
- Android：新增 `KRTerminalModule`（复用 `KRSftpClient` 会话的 JSch shell + pty，输出偏移轮询）、`clipboardSupported/copyToClipboard`、`cacheRoot/clearCache`；`KRSftpClient.download` 支持**绝对路径** localName（缓存落盘）。
- iOS/macOS：新增 `KRTerminalModule`（复用 `KRSftpSession` 的 NMSSH 会话，`requestPty + startShell`，`NMSSHChannelDelegate` 原始字节累积 + 偏移拉取；pty resize 因 NMSSH 未公开 API 为 no-op），`supportsTerminal` 置 YES；`KRBridgeModule` 另加 `clipboard/cacheRoot/clearCache/supportsXterm`；**必须 `pod install`** 让新源文件进 Pod。
- MiniApp：`copyToClipboard` 走 `NativeApi.plat.setClipboardData`（wx）；无本地目录/终端底座 → `cacheRoot=""`、`supportsTerminal=false`（入口隐藏，绝不伪报）。
- OHOS：`KRBridgeModule.ets` 新增 `supportsXterm/SUPPORTS_TERMINAL(true)/clipboardSupported/copyToClipboard(pasteboard)/cacheRoot(cacheDir)/clearCache`，`syncMode=true`
  （⚠️ **ETS 未在本机编译**：需要 DevEco/hvigor）；新增 C++ `KRTerminalModule`（复用 `KRSftpSession` 保存的凭据**单开一条独立 SSH 连接** + `openssh` shell/pty，读取线程阻塞式读取、输出偏移拉取；`libkuikly.so` **CMake 编译通过**）。
- 编译结果：**Android `assembleDebug` ✅ / iOS `xcodebuild` ✅ / macOS `xcodebuild` ✅ / OHOS 渲染器 CMake `libkuikly.so` ✅ / `:demo`+`:h5App`+`:miniApp` JS ✅**（OHOS 的 ETS 需 DevEco 编译，本机未验）。
- Electron 用例：`features 25/25`、`smoke 22/22`、`term 7/7`（S9l 在无自动化权限时用 CDP `Emulation` 改视口回退，仍验证同一条 resize 链路）。
- **双栏文件管理跨端化（本轮完成）**：`core/file-manager` 扩到 `jvm/js/android/ios(x64,arm64,simArm64)/macos(x64,arm64)`，
  OHOS 走**单独 build 文件** `core/file-manager/build.ohos.gradle.kts`（含 `ohosArm64`）+ `settings.2.0.ohos.gradle.kts` 单独 include（标准 Kotlin 不识别 `ohosArm64`）；
  `PlatformTime` 补 android/apple(`NSDate`)/ohos(`posix.time`) actual。`FilesDualPanePage` 迁回 **commonMain**，本地栏改走 `BridgeModule.lfXxx`；
  Android/iOS/macOS 各实现沙盒本地文件（`filesDir/local`、`Documents/local`，越界拒绝）。用例：`test:dual` **28/28**（Electron）。
- **仍待办**：OHOS/MiniApp 的本地文件系统（ETS / 无底座）→ 双栏本地栏在两端隐藏；MiniApp 缓存/终端无底座；OHOS ETS 需 DevEco 内编译验证。

---

## 1. 项目目标


一份 KMP 代码六端运行的 SFTP 客户端，支持：
- 完整文件管理：连接 / 浏览 / 上传 / 下载 / mkdir / rm / rename / move / copy / chmod / chown / setMtime / 批量
- 文件收藏 + 播放历史（LRU 2000 条）+ 连接持久化
- 流式媒体播放（点开即播、Seek、续播提示、选集）
- 7 类文档预览（Text / Markdown / HTML / Image / PDF / Audio / 视频帧）

---

## 2. 六端现状矩阵（2026-09-22 实测）

| 端 | 构建 | 协议栈 | 本地代理 | 运行/功能验证 | 状态 |
|----|------|--------|----------|---------------|------|
| **macOS** | ✅ `xcodebuild` BUILD SUCCEEDED | NMSSH (libssh2 1.10.0) | GCDWebServer | ✅ 界面操控验证（播放 / 拖动 seek / 暂停恢复） | **全链路可用** |
| **iOS** | ✅ `xcodebuild` BUILD SUCCEEDED | NMSSH (libssh2 1.10.0) | GCDWebServer | ✅ 模拟器 74/74 自测 + AVPlayer 播放 | **全链路可用** |
| **Android** | ✅ `./gradlew :androidApp:assembleDebug` | JSch 0.1.55 | NanoHTTPD | ✅ 模拟器 **74/74 × 10 轮零失败** + ExoPlayer 经代理播放 | **全链路可用** |
| **HarmonyOS** | ✅ 渲染器 `libkuikly.so` + 业务 `libshared.so` | libssh2 + 自 vendored mbedTLS 2.28.8 | ❌ 桩（未进 CMake） | ❌ 运行时未验证（无设备） | **核心可用、播放未实现** |
| **Web (H5)** | ✅ `:demo:packLocalJsBundleDebug` + `:h5App:jsBrowserDevelopmentWebpack` | 浏览器无 socket → Node 网关代持（ssh2） | 网关直接出 HTTP Range | ✅ 浏览器实测（连接 / 浏览 / Range / 拖动 seek） | **全链路可用** |
| **桌面壳 (Electron)** | ✅ `npm run dist:release`（dmg + `/Applications` 覆盖安装） | 复用 Node 网关（打包进 `Resources/gateway`） | 网关 Range | ✅ `features 25/25`、`smoke 21/21`、`player 8/8`、`text 16/16`、`dual 28/28`、`term 7/7` | **全链路可用（含独立窗口：播放/终端/文本查看器）** |
| **小程序** | ✅ 同 Web（共用 JS bundle）+ `:miniApp:jsMiniAppDevelopmentWebpack` | 复用 Web 的 JS 模块 | 需网关 | ❌ 未验证（需微信域名白名单） | **未验证** |

---

## 3. 已交付能力清单

### 3.1 commonMain 共享层（100% 六端共享，零原生依赖）

**能力声明**（`core/src/commonMain/.../module/sftp/`）：
- `SftpModule` — 19 个方法：connect / disconnect / list / stat / openRead / read / close / download / upload / mkdir / rm / rename / move / copy / chmod / chown / setMtime / batchTask / cancelBatchTask
- `SftpConnectionModule` — 7 个方法：add / update / remove / list / get / testConnection / clearAll
- `SftpFavoritesModule` — 7 个方法：add / remove / removeByConnection / list / isFavorited / update / search
- `SftpPlaybackHistoryModule` — 7 个方法：upsert / get / listByDirectory / listByConnection / remove / clearByConnection / markCompleted
- `SftpMediaProxyModule` — 4 个方法：startOrGetPort / registerToken / unregisterToken / stop
- 数据模型：`SftpConnection` / `SftpConnectParam` / `SftpEntry` / `SftpFavorite` / `SftpBatchTask` / `SftpPlaybackRecord` / `SftpCopyResult` / `OverwriteMode` / `AuthMethod`
- `SftpErrorCode`（1001-9999 分段）+ `SftpErrorFormatter` 统一错误码跨端语义
- `MimeExtMap`（扩展名 → MIME）+ `SftpMediaUrlBuilder`（`http://127.0.0.1:<port>/<token>/<name>`）
- `I18n`（中英文）+ `EncodingDetector`（UTF-8 BOM / GBK / Latin-1 嗅探）

**UI 页面**（`demo/src/commonMain/.../pages/sftp/`，自研 DSL）：
- `SftpHomePage` — 连接列表 + 新建/编辑/删除 + 测试连接 + 收藏 Tab + 历史 Tab
- `SftpBrowserPage` — 目录浏览 + 排序 + 过滤 + 多选 + **收藏两态按钮（未收藏 ☆ / 已收藏 ★，点 ★ 取消）** + 面包屑 + 三态
- `SftpPlayerPage` — 视频播放 + 续播提示 + 选集抽屉 + 自动下一集倒计时 + 首帧超时 + mpv OSC 风格控件
- `SftpFavoritesPage` / `SftpHistoryPage` / `SftpFilePropsPage` / `SftpBatchProgressDialog` / `SftpConnectEditPage`
- `SftpIntegrationTestPage` — 74 项集成自测页，逐条断言后落日志
- 7 类预览查看器：`SftpTextViewer` / `SftpMarkdownViewer` / `SftpHtmlViewer` / `SftpImageViewer` / `SftpPdfViewer` / `SftpAudioViewer` / `SftpVideoFrameViewer`
- `SftpViewerDispatcherPage` — 通过 provider 注入 token，避免组件同步取代理导致恒空（§13.3 第 3 条已修）

### 3.2 各端原生实现

| 端 | 原生目录 | 关键模块 |
|----|---------|----------|
| Android | `core-render-android/.../expand/module/KRSftp*.kt` | JSch + NanoHTTPD 本地代理 |
| iOS / macOS | `core-render-ios/Extension/Modules/KRSftp*.m` + `KRLocalHttpProxy.m` | NMSSH + GCDWebServer（iOS 播放器已从 WMPlayer 换成 AVPlayer + AVPlayerLayer） |
| HarmonyOS | `core-render-ohos/src/main/cpp/.../sftp/` | libssh2 + 自 vendored mbedTLS 静态库；`KRSftpSession` / `KRSftpFileHandle` / 3 个存储模块；本地代理桩未进 CMake |
| Web (H5) | `sftp-gateway/server.js`（Node）+ `h5App/src/jsMain/kotlin/module/SftpGatewayModules.kt` | 浏览器通过 HTTP RPC 调用 Node 网关代持 SSH/SFTP |

### 3.3 Web (H5) 架构（2026-09-22 完整落地）

```
浏览器（Kuikly Web）                       Node 网关 sftp-gateway/
  SftpModule 等 5 个 JS 模块 ──POST /rpc──▶  ssh2 会话
  <video>  ◀── HTTP Range /<token>/<file> ──  直接以 Range 读远端文件
```

- 网关：`sftp-gateway/server.js`，默认 `127.0.0.1:18090`
  - `POST /rpc {module, method, params}` — `sftp`（19 方法）+ `mediaProxy` + `connection` / `favorites` / `history`（JSON 文件持久化到 `sftp-gateway/data/`，已 gitignore）
  - `GET /<token>/<fileName>` — HTTP Range（206/416 + Content-Length）
- 浏览器模块：`h5App/src/jsMain/kotlin/module/SftpGatewayModules.kt`（5 个类转发到 `/rpc`），在 `KuiklyWebRenderViewDelegator.registerExternalModule` 注册
- 宿主 → 页面事件（`h5App/src/jsMain/kotlin/Main.kt`）：
  - `mousemove/touchstart` → `sftp_controls_activity`（全屏播放时移动鼠标唤醒控制条）
  - `keydown` → `sftp_player_key`（空格/K、←/→、M、F；输入框内不拦截）
  - `fullscreenchange` → `sftp_fullscreen_changed`（ESC 退出全屏时同步页面状态）

---

## 4. 本轮交付（2026-09-22，2 个 commit）

### 4.1 `839db48d` — `fix(web): 修复 Web 端 SFTP 白屏（PagerNotFoundException）并启用 SPA 路由`

解决 MyFlicker 内置浏览器访问 SFTP 专题页的两类阻断：

**阻断 1：PagerNotFoundException 白屏**
- 根因：Kuikly KSP 在 JS/Web/MiniApp 目标上只生成页面名注释（供 Gradle 插件 `JSProcessor` 做分包/产物切分），不生成运行时注册代码；`core-render-web` 自身也从不调用 `PagerManager.registerPageRouter`。结果 `pagerNameMap` 恒为空，`createPager` 抛 `PagerNotFoundException` → 白屏。
- 修复：demo jsMain 新增 `WebMain.kt`（executable 入口，webpack 加载时自动执行 `main()`），调用 `WebSftpPageRegistry.registerSftpWebPages()` 注册 11 个 SFTP 专题页面 + demo 入口 router 页面。幂等（`isPageExist` 判重），host 零改动。
- 配套：
  - `h5App Main.kt` 在创建页面前显式调用 `registerKuiklyDemoWebPages()`（双保险）
  - `h5App index.html` 改用相对路径加载 `nativevue2.js`，并暴露 `window.callKotlinMethod` / `registerCallNative` 桥（兼容老 Web 渲染 bundle 直调 window 函数的写法）
  - `core/jsMain NativeBridge.kt` 用 `@JsName("callKotlinMethod")` + `@JsExport` 导出桥入口
  - `core-render-web KuiklyRenderContextHandler.kt` 改用 `com.tencent.kuikly.core.nvi.callKotlinMethod` 命名空间调用
  - `core PagerManager.kt` 解析 URL 时同时接受 `page_name`（H5 宿主）与 `pagerName`（原生宿主）

**阻断 2：点击已连接的连接不跳回首页（`closePage()` 被浏览器静默拒绝）**
- 根因：`KuiklyRouter.ENABLE_BY_DEFAULT = false` → SPA 路由未激活，`closePage` 落到 `window.close()`，而浏览器对「非 `window.open` 打开的页面」会静默拒绝；`openPage` 落到 `window.open()` 会开新标签而非页内跳转。
- 修复：`ENABLE_BY_DEFAULT = true`，让 `init()` 始终注册 `globalNavigationHandler` / `globalClosePageHandler`：
  - `openPage(url)` → `push(url)` → `window.history.pushState()`（页内前进，不开新标签）
  - `closePage()` → `back()` → `window.history.back()`（页内返回，浏览器不拒绝）
  - 附带消除 CDP 导航后 `webContentsId` 变化导致的会话断开

**运行时验证**：
- `./gradlew :h5App:jsBrowserDevelopmentWebpack` BUILD SUCCESSFUL，bundle 中 `handleEntry` 已编译为 `=== '1' || true; if (useSpa) init(this)`，SPA 必激活
- 内置浏览器 `?page_name=SftpHomePage` 渲染正常，连接列表显示 `kw-test` / `kw-e2e`
- 网关 17 步全过：连接 / 列目录 / 上传 / 下载 / Range / chmod / setMtime / copy / batchTask / 视频流

### 4.2 `042c4784` — `refactor(sftp): 播放页控件改用 mpv OSC 风格（两行布局 + 主题 token 化）`

精读 mpv `osc.lua` 362KB 源码后，把 SFTP 播放页控件从单行 Plyr 风格改造成 mpv `bottombar` 两行布局：
- `SftpPlayerPage.kt` — 控件层改为两行（信息行 + 控制行），所有硬编码颜色/尺寸替换为 `SftpPlayerTokens.*`，所有字符图标替换为 `SftpPlayerIcons.*`
- `theme/SftpPlayerTokens.kt` — 对齐 `osc.lua user_opts` 的 10+ OSC 专属色与尺寸 token
- `theme/SftpPlayerIcons.kt` — 两套图标（Classic + Modern），覆盖播放/暂停/快进/快退/全屏/静音/选集等
- `devDocs/sftp-player-modernz-plan.md` — 完整改造方案文档（mpv OSC 设计哲学提炼 + 跨平台要点清单 + 能力矩阵 + 交互/全屏/视频组件/本地代理的跨端差异映射 + 降级策略 + 编译验证命令）

仅 commonMain 实现，六端共享，不引入新依赖。

---

## 5. 关键架构决策（不可回退）

1. **本地媒体代理统一走 Module**，不再用 `expect/actual`（历史上两套并存，`LocalMediaProxyApi` 已删除）
2. **业务/UI 100% 在 commonMain**：`demo/.../pages/sftp/` 六端共享
3. **能力声明 100% 在 commonMain**：`core/.../module/sftp/`
4. **原生实现按端隔离**：Module 名 == 原生类名，框架用 `NSClassFromString` / 注册表解析；入参 JSON 字符串，出参 JSON
5. **Web 端 Node 网关代持 SSH/SFTP**：浏览器无法建立原始 TCP/SSH，必须由后端进程代持，浏览器只做桥
6. **Kuikly KSP 在 JS/Web/MiniApp 不生成运行时注册代码**：必须由 demo jsMain 入口 `WebMain.kt` 在 bundle 加载时显式注册（见 §4.1）
7. **Web SPA 路由默认启用**：`KuiklyRouter.ENABLE_BY_DEFAULT = true`，`openPage`/`closePage` 走 `history.pushState` / `history.back()`，不走 `window.open` / `window.close`

---

## 6. 运行时验证步骤（Web 端）

### 6.1 起服务

```bash
# 1) Node 网关
cd sftp-gateway && npm install
PATH="$HOME/.gradle/nodejs/node-v22.0.0-darwin-arm64/bin:$PATH" node server.js
# 网关监听 127.0.0.1:18090

# 2) 业务 bundle + 宿主（必须 JDK 17）
export JAVA_HOME=/Users/zhaojian/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home
./gradlew :demo:packLocalJsBundleDebug -Pkuikly.useLocalKsp=false
./gradlew :h5App:jsBrowserDevelopmentWebpack
cp h5App/build/kotlin-webpack/js/developmentExecutable/h5App.js \
   h5App/build/processedResources/js/main/h5App.js
cp demo/build/kotlin-webpack/js/developmentExecutable/nativevue2.js \
   h5App/build/processedResources/js/main/nativevue2.js

# 3) 静态托管（8080 出 index.html + h5App.js + nativevue2.js）
cd h5App/build/processedResources/js/main
python3 -m http.server 8080 --bind 127.0.0.1
```

### 6.2 浏览器访问

在内置浏览器打开 `http://127.0.0.1:8080/?page_name=SftpHomePage`，期望：
1. 首页显示连接列表
2. 点击 `kw-e2e` → 页内跳转到 `SftpBrowserPage`（URL 变为 `page_name=SftpBrowserPage`，不开新标签）
3. 浏览页点返回 → 页内回到 `SftpHomePage`（URL 变回 `page_name=SftpHomePage`，不 reload）
4. 列表应显示 `kw-test` + `kw-e2e` 两条

### 6.3 网关 17 步自测

见 `h5App/build/processedResources/js/main/kr-selftest.html`（2026-09-22 实测 17/17 通过、耗时 2649ms）。

---

## 7. 已知限制 / 待办

### 7.1 跨端能力缺口

| 项 | 说明 | 优先级 |
|----|------|--------|
| **OHOS 本地代理 + 播放器** | `KRLocalHttpProxy` 仍是桩，未进 CMake；`core-render-ohos` 也没有视频组件 → OHOS 不支持播放 SFTP 视频 | 中（需真机） |
| **OHOS 运行时验证** | 本机无 OHOS 设备/模拟器镜像，核心已实现但未真机验证 | 高（需设备） |
| **小程序验证** | 复用 Web 的 JS 模块，需网关可达 + 微信 request 域名白名单 | 低 |
| **Web 网关会话内存态** | 重启网关即失效，旧 `sessionId` 的播放页 seek 会失败，需从首页重进 | 中 |
| **known_hosts 校验** | 四端均未做（安全审计 P0） | 高 |
| **Web 缓冲条** | 仅 Web 有（其它端引擎未回传 `buffered`） | 低 |
| **音量滑杆** | `VideoView` 无 volume 属性 | 低 |
| **`overwrite` 行为** | OHOS 与 Android 均未实现 `SKIP` / `FAIL` / `RENAME_APPEND_SUFFIX`，一律按覆盖处理 | 中 |

### 7.2 播放页 mpv OSC 改造后续

`devDocs/sftp-player-modernz-plan.md` 列出的能力清单中，本轮只落地了**两行布局 + token 化**，尚未落地的：
- 鼠标 3 键 + 滚轮交互（移动端无鼠标，需跨端降级）
- 进度条 3 种样式 × 5 种缓存范围样式
- 悬停缩略图预览（thumbfast）
- 右键菜单系统（select.lua 联动）
- 可见性系统（3 模式循环 + 死区）
- 60fps 节流刷新
- 日/夜模式切换

### 7.3 仓库卫生

未纳入 git 的参考目录（不打算提交）：
- `ModernZ/` — 第三方 mpv GUI 仓库（参考用）
- `mpv/` — 第三方 mpv 源码（参考用）
- `.codeflicker/` — IDE 状态目录

---

## 8. 提交时间线（2026-09-22 本轮）

```
042c4784  refactor(sftp): 播放页控件改用 mpv OSC 风格（两行布局 + 主题 token 化）
839db48d  fix(web): 修复 Web 端 SFTP 白屏（PagerNotFoundException）并启用 SPA 路由
0dc0d1cb  docs: 更新 AGENTS.md —— Web/JS 端 SFTP 落地（Node 网关 + 浏览器模块）与六端现状
be71901c  feat(web): 新增 Web 端 SFTP（Node 网关 + 浏览器模块）并重做跨端播放控件
```

完整时间线见 `git log --oneline --date=short`。远程 `origin/zhaojian` 与本地 HEAD 同步在 `042c4784`。

---

## 9. 关键文件索引（按调用方向）

- **业务/UI（KMP，六端共享）**：`demo/src/commonMain/.../pages/sftp/`
- **能力声明**：`core/src/commonMain/.../module/sftp/`（5 个 Module + 数据模型 + I18n + MimeExtMap + SftpMediaUrlBuilder + SftpErrorCode）
- **Web 入口**：`demo/src/jsMain/.../WebMain.kt` + `WebSftpPageRegistry.kt`（bundle 加载时注册 11 个页面）
- **Web SPA 路由**：`h5App/src/jsMain/.../manager/KuiklyRouter.kt`（`ENABLE_BY_DEFAULT = true`）
- **Web 宿主桥接**：`h5App/src/jsMain/.../Main.kt` + `module/KRRouterModule.kt` + `module/SftpGatewayModules.kt`
- **Web 视频组件**：`core-render-web/base/src/jsMain/.../expand/components/KRVideoView.kt`
- **Web 网关（Node）**：`sftp-gateway/server.js` + `package.json`
- **Android 原生**：`core-render-android/.../expand/module/KRSftp*.kt` + `LocalHttpProxyServer.kt`
- **iOS / macOS 原生**：`core-render-ios/Extension/Modules/KRSftp*.m` + `KRLocalHttpProxy.m`
  - macOS 播放器：`macApp/.../Handlers/KRVideoViewHandler.{h,m}`（VLCKit）
  - iOS 播放器：`iosApp/iosApp/KuiklyRenderExpand/`（AVPlayer + AVPlayerLayer）
- **HarmonyOS 原生**：`core-render-ohos/src/main/cpp/.../sftp/` + `thirdparty/libssh2-ohos/arm64-v8a/`
- **播放页主题**：`demo/.../pages/sftp/theme/SftpPlayerTokens.kt` + `SftpPlayerIcons.kt`
- **完整设计文档**：`docs/SFTP-Client.md`（§23 跨平台架构）
- **实现详解**：`docs/SFTP-实现详解.md`
- **落地计划**：`devDocs/sftp-impl-plan.md`
- **播放页改造方案**：`devDocs/sftp-player-modernz-plan.md`


---

## 本轮新增：双栏文件管理器（Web / 桌面）

**已交付并自动化验证（22/22）**：

- `core/file-manager/`：纯状态机共享核心（路径越界拦截、排序过滤、选中、操作与传输计划），jvm+js **76/76** 单测；
  目录优先排序。
- `demo/src/jsMain/.../FilesDualPanePage.kt`：双栏 UI（活动栏、行选中/`▶` 进入、新建/重命名/删除弹层、
  主机会话切换器、未连远端提示）。
- 入口：首页默认「本地文件管理」；首页连接行「⇄」；浏览页右上「⇄」（带当前目录）。
- Electron 宿主：`localfs:*` IPC（含 `readFile/writeFile`），根=用户主目录，越界拒绝。
- 网关扩展：`upload` 支持 `localPath`（无 `content` 时直读本地）；`download` 支持绝对路径直写。
- 验证：`electron/test/dual-pane.mjs`（D0–D19，真实 CDP 鼠标点击 + 关键步骤截图 + 字节级校验 + 夹具自建自清）。

**未落地**：目录递归传输、断点续传/重试（`packages/file-transfer` 引擎）、拖拽、远程编辑器、多远端同屏、
H5（非 Electron）本地栏。


### 审查修复轮（同轮完成）

代码审查（16 项）已全部处理并通过回归：

- **安全（CRITICAL）**：网关 `localPath` 上传 / 绝对路径下载加 `realpath` 根校验（越界 → 2001）、上传改流式；
  Electron `assertWithinRoot` 改 realpath（防符号链接越界）、`writeFile` 拒写符号链接；浏览页「⇄」改为只传
  `connectionId`（凭据不再进 URL/history，必要时先落加密连接库）。
- **功能/WARNING**：移除与页内桥重复的 Electron 宿主事件注入（按键双发 → toggle 失效）；列表陈旧响应加请求校验；
  删除失败不再伪报成功；活动栏默认本地、未连远端给可见提示；计划里的 `overwrite` 真正下发；无本地宿主时降级提示。
- **清理**：`sync` 拆分（选中不再重建整栏行）、共享 `isWebLike` 与 `paramFactory` 去重、`planRemove` 去死参、
  去掉死 `mediaTokens.set` 与无效三元/未用 import。
- **新增回归用例**：D20–D25（越界拒绝、根内可用、本地栏增删、URL 无凭据），双栏套件 **28/28**。


---

## 本轮修复：桌面端播放器（Web / Electron）三个真实缺陷 + 用例补全

| 缺陷 | 现象 | 修复 |
|---|---|---|
| Web `KRVideoView` 未实现 `seekTo` | 拖动进度条时 tooltip 跟着走但**视频不跳**；键盘 ←/→、续播也无效（Web 端 seek 全链路失效） | 实现 `seekTo`（含元数据未就绪时暂存、`loadeddata` 后补发） |
| 播放器全屏浮层未绝对定位 | 打开**选集抽屉**后 `video` 元素高度塌成 0 → **上半屏纯黑**（不是半透明） | 抽屉/续播弹窗改 `positionAbsolute()` |
| `name`/`remotePath` 非 observable | 切换选集后标题停在旧文件名 | 改 `observable` |
| `seekTarget` 不复位 | 再次跳到同一位置因属性值未变化而不下发 | seek 落点后自动复位为 -1 |

**用例补全**（`electron/test/smoke.mjs`，现 **19/19**）：
- `S9f` 键盘 seek（原先可能"假通过"，现与 S9g 共同保证真跳转）
- `S9g` 拖动进度条 seek（真实按下-移动-松手，断言跳到目标比例）
- `S9h` 切换选集后可正常播放（标题更新 + 时间推进）
- `S9i` 选集抽屉打开后视频区不塌陷（`video` 高度 > 100，回归"黑屏"）

**打包**：新增 `npm run build:web:release` / `sync:release` / `dist:release`（release bundle 让 app.asar 43MB→9.9MB）。
release dmg 实测：拖动 `1.55s → 49.42s`（目标≈48s）、抽屉打开 `videoH=736`。


### 本轮修复：切换选集不播放 / 中途解码失败（+ 用例加严）

| 缺陷 | 根因 | 修复 |
|---|---|---|
| 切换选集后**不播放**（上一集播完/暂停后必现） | `isPlaying`（用户意图）在 `PLAY_END/ERROR` 被复位为 false，`switchToEpisode` 未置回 true | `switchToEpisode` / 续播弹窗两动作均置 `isPlaying = true`；Web 引擎侧另加 `wantPlay`，换源后 `loadeddata` 恢复播放 |
| 切换后播放到 ~2.3s **冻结**（`MEDIA_ERR_DECODE` err=3） | `switchToEpisode` 未更新 `size`，注册媒体 token 用了上一集的 size → 代理按错误长度截断 Range；且网关 `registerToken` **信任客户端 totalSize** | 播放页切集先 `stat` 刷新 size；网关 `registerToken` 一律以服务端 `stat` 为准（客户端值仅作兜底） |

**用例**：`S9h` 改为「**无任何按键**断言自动播放 + 连续观察 8s 断言无解码错误」；
按需求**暂缓全屏用例**（原 S9e 移除，S9f 不再依赖全屏）；当前 smoke **18/18**、双栏 28/28、core 76/76。

**规则固化**：`AGENTS.md §14.1` —— 每轮「测试全绿 + 修复完成」后固定：打包 → 覆盖安装 → 提交 → 推送。


---

## 本轮新增：独立窗口播放 + 从头播放 + 进度记录（含 1 个已知问题）

| 需求 | 结果 |
|---|---|
| 视频开为**独立窗口**（可同时开多个） | ✅ 已实现：`BridgeModule` 宿主能力 + Electron `createPlayerWindow`（上限 8 个窗口）；用例 **P0–P5 8/8**（开多个、逐个关闭、主窗口不受影响）|
| **从头播放按钮** | ✅ 控制条新增 `⏮`（清续播提示 → 回到 0 → 起播）；用例 **S9j** |
| **进度记录 / 续播** | ✅ 修复真实缺陷后可用：网关 `history.get/markCompleted` 与写入 id 规则不一致（页面写 `buildId` 哈希，网关按 `connectionId::remotePath` 查 → 永不命中）→ 已按三种方式依次匹配；另修：切集重置续播态、暂停即落盘、`%s` 占位符未替换；用例 **S9k** |
| 视频**随窗口改变大小** | ⚠️ **已知问题（未完成）**：Web 根视图 resize 链路失效（`updateRootViewSize` 从未被调用；Pager 仅带 densityInfo 时重排）→ 视频区不跟随窗口；用例 **S9l** 以 SKIP 保留证据（视口 1180→760 但 video 宽恒 1180）|

**测试**：smoke **20/20 + 1 SKIP（S9l）**、独立窗口 **8/8**、双栏 **28/28**、core **76/76**（含尾看门狗，全自动无需人工）。
另修测试基建：每条用例输出耗时、全局 900s 看门狗（未结束才报）、套件结束强制退出（避免残留进程）。


---

## 本轮新增：文本文件查看器（独立窗口，含 Markdown）

**参考实现**：MarkText（MIT，Electron Markdown 阅读/编辑器，渲染范围）+ VS Code/Monaco（只读查看：行号/换行/字号/字数）。

**实现**（全部在 commonMain，六端共用；本轮只验证 Electron）：

| 能力 | 说明 |
|---|---|
| 独立窗口 | 浏览页点文本类（md/html/txt/代码）→ 另开窗口（`standalone=1`，返回键关窗）；其它端回退页内路由 |
| Markdown 渲染 | 标题(色条)/段落行内样式(`RichText+Span` 单文本流)/代码块/引用/列表(含任务)/表格/分隔线 |
| 阅读器工具 | 目录(TOC) 抽屉、源码⇄预览、换行开关、A−/A+ 字号（实测 26→33.8px）、状态栏(编码·大小·行·字) |
| 纯文本 | 行号槽 + 等宽 + 换行/字号；渲染上限 1500 行；>2MB 只读前 2MB 并提示 |
| 装载 | `SftpTextLoader`：96KB 分块流式读 + 跨端解码（UTF-8 含 emoji / UTF-16 BOM） |

**本轮修掉的真实缺陷**（都是"看起来功能没做，其实是链路断"）：
1. `decideViewer` 把**路径**传给了期望 **mime** 的 `isMarkdown/isHtml` → `.md` 一直按纯文本渲染。
2. 分发页用结构层 `when{ctx.loading}` 做分支（非 `vif`）→ 加载完成后永远停在「加载中…」。
3. Markdown 解析器的任务列表**正则**在 Kotlin/JS 抛 `Lone quantifier brackets` → 正文空白；改为字符串解析。
4. 查看器正文/工具条标签/字号/目录数在结构层读状态 → 依赖不被收集 → 空态/开关不生效；改为 provider + `attr{}`/`vif`。

**用例**：`npm run test:text` → **T0–T6 11/11**（含用户指定目录 `/home/zhaojian/ks-cr-doc`）；
全量：文本 11/11、播放窗口 8/8、双栏 28/28、smoke 20/20(+1 SKIP)、core 76/76。


---

## 本轮：Markdown「即时渲染」编辑（参考 Vditor IR）+ 保存 + 目录二级弹窗

**参考 Vditor**（https://github.com/Vanessa219/vditor，MIT）：取其 **IR 即时渲染**（改完立即看到渲染结果）、
**工具栏**（标题/加粗/斜体/删除线/行内代码/代码块/引用/列表/任务/链接/表格/分隔线）、**toolbarConfig.pin**（工具条置顶紧凑）、
显式保存；未取 sv 分屏与 mermaid/katex 等重型渲染。

| 能力 | 实现 | 用例 |
|---|---|---|
| 即时渲染 | 编辑模式点块 → 浮层内格式工具条 + 编辑区 + **实时预览**（防抖 450ms 自动重渲染） | **T7**：点 B → 实时预览**自动加粗**（未点应用）✓ T10 ✓ |
| 应用改动 | 「应用」把该块 Markdown 源码替换回文档并立即重排 | **T7b** 正文立即变为加粗 ✓ |
| 保存 | 本地临时文件（localFs / FileModule）→ `SftpModule.upload(localPath)` 覆盖远端 | **T8** 读回远端含 `**` ✓ |
| 工具栏紧凑 | 单行置顶：`A− A+ 换行 源码 编辑 目录 N 保存` + 状态同行 | **T9** 高 51px、顶部 56px ✓ |
| 目录二级弹窗 | 底部圆角抽屉 + 独立滚动 + 层级缩进 + 近似跳转 | **T3d**（弹窗独有标记「目录 · N 项」）✓ |

**修掉的两个视觉缺陷**：① 浮层与内容区同级且在前面 → 被正文遮盖（"只看到一条遮罩"）→ 移到内容区之后；
② 浮层按钮与工具条同名（完成）→ 自动化点到开关 → 改名「应用」。

**测试**：`npm run test:text` **16/16**；全量 文本 16/16、播放窗口 8/8、双栏 28/28、smoke 20/20(+1 SKIP)、core 76/76。


---

## 本轮：终端（本地/远程 shell，独立窗口）

**方案选型**（按「每个功能都要考虑跨平台」）：不做 Rust FFI；**shell 通道走 Module 契约**（web → 本地 Node 网关 ssh2；
native → libssh2 pty 待接），**渲染有共享降级实现**（commonMain `TerminalBuffer` + `TerminalGridView`，六端共用），
web 可选 **xterm.js**（MIT，本地 vendor）加速。参考意见中的 ①②③ 均不采用（Rust FFI / 纯 Kotlin 无降级 / AGPL）。

| 能力 | 结果 |
|---|---|
| 入口 | 首页「本地文件管理」栏右侧 `>_`（本地终端）+ 每个连接行右侧 `>_`（远程终端），均**独立窗口** |
| 本地终端 | **SSH 本机**（127.0.0.1）：首次弹**账号密码弹窗**，「记住」后写入连接库（label「本机」）下次直连 |
| 远程终端 | 复用连接库凭据 → ssh2 `conn.shell()` pty；实测 `whoami` 回显 `zhaojian` |
| 共享渲染 | `TerminalBuffer`（ANSI-lite：CSI 光标/清屏/宽字符）+ `TerminalGridView`；**native 接入只需实现 shell 模块** |
| 输入 | Kuikly 输入行 + 宿主事件通道（`terminal_input`，xterm `onData` 同一条）|
| 用例 | `npm run test:term` **7/7**（入口/独立窗口/本地终端/输入回显/每行入口/远程 whoami/无异常）|

全量回归：终端 7/7、文本 16/16、播放窗口 8/8、双栏 28/28、smoke 20/20(+1 SKIP)、core 76/76。
