# SFTP 跨平台客户端 — 开发进度总览

> 本文件是 SFTP 客户端专题的**当前进度快照**，配合以下文档一起看：
> - `AGENTS.md` §13 — 专题压缩上下文（必读）
> - `docs/SFTP-Client.md` — 3590+ 行完整设计文档（§23 跨平台架构）
> - `docs/SFTP-实现详解.md` — 学习/实现向完整走读
> - `devDocs/sftp-impl-plan.md` — 全平台 Phase 0~6 落地清单
> - `devDocs/sftp-player-modernz-plan.md` — 播放页 mpv OSC 改造方案
>
> 最近一次更新：2026-09-22（`042c4784`）

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
- `SftpBrowserPage` — 目录浏览 + 排序 + 过滤 + 多选 + 心心收藏 + 面包屑 + 三态
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
