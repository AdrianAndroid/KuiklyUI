# Kuikly SFTP 客户端 — 交接文档（给下一个模型）

> **怎么用这份文档**：先读第 1、2 节建立整体认知，再按任务跳到第 3–6 节；
> 动手前**必须**读第 7 节（规则）与第 8 节（环境/命令）。
> 更细的专题内容在 `AGENTS.md §13`（SFTP 专题，最权威）与 `devDocs/*.md`（各阶段方案/测试规程）。
>
> 仓库根：`/Users/zhaojian/bin/macmini/KuiklyUI`，开发分支 **`zhaojian`**（`.kilo/worktrees/*` 是旧的隔离工作树，别在里面提交）。
> 最近验证时间：2026-09-27。

---

## 1. 项目一句话与现状

在 **KuiklyUI（KMP 跨端 UI 框架，六端）** 之上实现一个**跨端 SFTP 客户端**：
账户管理 → 浏览远端目录 → 上传/下载/批量文件操作 → 本地缓存 → 音视频/文档预览与编辑 → 终端 → 双栏文件管理。
桌面端由 **Electron** 宿主承载，Web/小程序通过 **Node 网关**代持 SSH。

**现状（可用性，不是"文件是否存在"）**：

| 端 | 状态 |
|---|---|
| Electron / Web(H5) | ✅ 主力验证端：功能最全，自动化用例最完整（见 §5） |
| Android | ✅ SFTP 全链路 + 本地代理播放（模拟器实测通过） |
| iOS | ✅ 构建通过（-Werror）、SFTP 链路可用 |
| macOS | ✅ 构建通过、界面链路可用 |
| HarmonyOS | ⚠️ SFTP 核心已实现并**编译/链接通过**（渲染器 + 业务 .so），**运行时未验证**（无设备/模拟器镜像）；**视频播放未实现** |
| 小程序 | ⚠️ 与 Web 同构（浏览器沙箱限制，走网关）；构建通过 |

构建/验证矩阵的逐端命令与结果见 **`AGENTS.md §13.1.1`、`§13.1.2`**（含"六端全部编译一遍"实测与我修掉的阻断项）。

---

## 2. 功能清单（用户可见能力）

| 功能域 | 内容 | 主要落点 |
|---|---|---|
| 连接与凭据 | 连接列表 CRUD（label 去重）、密码/公钥认证、`touchLastUsed` 排序、删除连接级联清理收藏/历史 | `core/.../module/sftp/SftpConnectionModule.kt` + 各端原生 |
| 目录浏览 | list/stat、隐藏文件开关、增量加载、路径复制、文件属性页、批量选择 | `demo/.../sftp/SftpBrowserPage.kt`、`SftpFilePropsPage.kt` |
| 传输 | 上传/下载（offset 续传）、批量任务（DELETE/MOVE/COPY/DOWNLOAD）、进度与取消 | `SftpModule.kt`、`SftpBatchProgressDialog.kt` |
| 收藏 / 播放历史 | 收藏目录与文件（可备注）、按连接/目录查询历史、历史容量设置、LRU 上限 | `SftpFavoritesModule.kt`、`SftpPlaybackHistoryModule.kt`、`SftpFavoritesPage.kt`、`SftpHistoryPage.kt` |
| 缓存 | 目录/单文件缓存到本地（Web/桌面），进度浮层、暂停/继续/取消/清空 | `demo/.../sftp/cache/{CacheManager,CacheEngine,CacheListOverlay}.kt` |
| 预览与编辑 | Markdown（渲染 + IR 即时编辑 + 保存）、纯文本（行号/换行/字号）、代码（语法高亮）、HTML/图片/PDF/音视频；**独立窗口**打开 | `demo/.../sftp/viewer/**` |
| 图表 | **Mermaid 流程图**（flowchart/graph 子集，共享降级实现，六端可用） | `viewer/md/MermaidFlowchart.kt`、`viewer/SftpDiagramView.kt` |
| 媒体播放 | 经**本地 HTTP 代理**把远端文件喂给播放器（Range/206），播放历史续播、倍速、控制栏自动隐藏 | `SftpMediaProxyModule.kt`、`SftpPlayerPage.kt`、各端 `KRLocalHttpProxy`/网关 |
| 终端 | 本地/远程 shell，独立窗口；共享 `TerminalBuffer` + `TerminalGridView`，Web 可选 xterm.js | `demo/.../sftp/terminal/**`、`KRTerminalModule`（OHOS） |
| 双栏文件管理 | 左右栏（本地 ⇄ 远端）、跨栏复制/移动、选择与批量 | `FilesDualPanePage.kt` |
| 调试 | 开发态显示 `DEBUG` 标识（Release 不显示） | `AGENTS §13.1.8` |

---

## 3. 架构逻辑（重点）

### 3.1 分层

```
┌─────────────────────────── commonMain（六端共用，禁止依赖 core-render-*） ───────────────────────────┐
│ 业务页面  demo/.../pages/sftp/**            （浏览/收藏/历史/查看器/播放/终端/双栏/设置）              │
│ 能力契约  core/.../module/sftp/**           （SftpModule / Connection / Favorites / PlaybackHistory  │
│              / MediaProxy / MediaUrlBuilder / Model / MimeExtMap / EncodingDetector / I18n）          │
└──────────────────────────────────────────┬───────────────────────────────────────────────────────────┘
                                           │ Module.asyncToNativeMethod / syncToNativeMethod（过桥）
        ┌──────────────────────────────────┼──────────────────────────────┬───────────────────────────┐
        ▼                                  ▼                              ▼                           ▼
   Android renderer                 iOS / macOS renderer            HarmonyOS renderer           Web / 小程序
   JSch 0.1.55                      NMSSH（libssh2 封装）           libssh2 1.11 + mbedTLS       Node 网关（ssh2）
   KRSftpClient.kt                  KRSftpSession.{h,m} 等          KRSftpSession.cpp 等         sftp-gateway/server.js
   + NanoHTTPD 本地代理                                             + 本地代理（未实现）
```

**关键约定**：能力声明与页面逻辑必须在 `commonMain`；各端只实现"宿主能力"（Module/native）。
未实现的端**必须显式不可用**（`BridgeModule.supportsXxx()` 探测 + 入口隐藏/提示），**绝不伪报成功**。

### 3.2 三条数据路径

1. **原生端（Android / iOS / macOS / OHOS）**：`SftpModule` → 各端原生 Module → SSH 库 → SFTP 服务器。
   随机读走 `openRead/read/close`（`offset` + `len`），二进制回包用**原子通道**（`[meta, ByteArray]`）。
2. **Web / 小程序**：浏览器无原始 socket → 全部经 `POST /rpc {module, method, params}` 到网关；
   连接/收藏/历史在 **localStorage** 实现（不经网关）；媒体流由网关直接以 `GET /<token>/<file>` + **Range/206** 提供。
3. **Electron 桌面**：`electron/main.js` 用 `utilityProcess` **内嵌同一份网关**（`SFTP_GATEWAY_PORT=0` → OS 分配端口），
   并额外提供本地能力：`localFs`（宿主文件读写）、终端 spawn、独立窗口（`standalone=1`）。

### 3.3 关键机制

- **本地媒体代理**：`SftpMediaProxyModule`（token 注册）+ 各端代理实现；`SftpMediaUrlBuilder.buildPlayUrl` 统一拼 URL，
  保证六端 URL 形态一致（网关与原生代理都按它实现）。
- **过桥类型限制**：`Module` 只支持 `String/Int/Float/ByteArray`；**裸 `JSONArray`/`JSONObject` 过不了桥**，
  统一用 `JSONObject` + 转 Map（历史上多次踩坑）。
- **响应式（本仓库经典坑）**：可变状态必须用 **provider 传入**，并在 **`attr{}` / `vif{}`** 内读取，否则依赖不被收集、
  界面不刷新；`vif` 只在**布尔值变化**时重建分支（值变化但真假不变不会重建）。
- **命令/状态分层**：副作用（申请代理端口、首次装载）放在 Pager（页面）而不是渲染组件里。

### 3.4 Markdown/查看器（近期改动最多，重点交接）

- 装载 `SftpTextLoader`（96KB 分块 + 跨端解码 UTF-8/UTF-16 BOM，>2MB 截断并提示）。
- 解析 `viewer/md/MarkdownParser.kt`：块级（标题/段落/代码/引用/列表/任务/表格/分隔线/图表）+
  行内（粗/斜/删除线/行内码/链接）。**行内片段在解析期预计算**（`Paragraph.runs` 等），渲染期不再重复解析。
- **图表**：` ```mermaid ` 归类为 `MdBlock.Diagram` → `MermaidFlowchart`（解析 + 最长路径分层）→ `SftpDiagramView`
  用**正交折线**绘制（不需要旋转/Canvas，六端可渲染）；解析失败回退代码块。
- **文档缓存** `SftpDocCache`：按 `connectionId::remotePath::size` 缓存文本 + 已解析 blocks/outline（LRU 8 篇），
  重开秒开、换行/字号等操作都基于缓存。
- **大文档性能**：改用**虚拟列表**（近期提交 `43bf4152`/`2a201ec8`），修复卡顿/滚轮失效/空白/跳回；
  早期版本用"增量窗口 + 点此加载更多"（40 块一批）。
- **IR 编辑**：点块编辑 → 工具条（H1–H3/B/I/S/行内码/代码块/引用/列表/任务/链接/表格/分隔线）+ 实时预览；
  工具条对行内格式**保留块级前缀且幂等**（`wrapInline`），避免 `> **x**` 被二次包裹破坏结构。
- 方案与取舍见 `devDocs/markdown-viewer-plan.md`（MarkText/Vditor 功能对照 + 路线图）。

---

## 4. 测试用例（现状）

**套件均在 `electron/test/`，用 CDP 驱动真实 Electron 应用 + 内嵌网关；用例自带看门狗与单步超时。**
下表"用例数"为**当前文件中 `check(...)` 调用点数量**（实际执行条数随分支/版本略有差异，套件结束会打印 `x/y 通过`）。

| 套件 | 命令 | 用例数 | 覆盖 |
|---|---|---|---|
| `smoke.mjs` | `npm test` | 23 | 入口/导航/独立窗口/播放等冒烟主链路（含 SKIP 记录 S9l 等） |
| `dual-pane.mjs` | `npm run test:dual` | 29 | 双栏：本地⇄远端、跨栏复制/移动、选择与批量 |
| `features.mjs` | `npm run test:features` | 36 | 收藏/历史/设置/缓存（F 系列）、删除连接二级确认 F26、回车确定 F31、半模态 F30 |
| `player-window.mjs` | `npm run test:player` | 10 | 独立播放窗口、续播、倍速、控制栏 |
| `text-viewer.mjs` | `npm run test:text` | 35（32 个唯一 ID） | 文本/Markdown 查看器 T 系列：渲染/目录/字号/换行/源码、IR 即时编辑与保存、Mermaid（T11）、增量渲染与缓存（T12/T13）、**虚拟列表回归 T14–T20**（长代码折行/编辑区滚动/滚轮稳定不白屏） |
| `terminal.mjs` | `npm run test:term` | 9 | 终端入口/独立窗口/本地终端/输入回显/远程 whoami |
| `env.mjs` | — | 0 | 测试环境（实例端口/隔离 userData/localRoot）公共装配 |

> 说明：`text-viewer.mjs` 的 T14–T20 与虚拟列表相关用例是"大文档卡顿/滚轮失效/白屏"修复后新增的回归项，
> 最近一次完整回归尚未在稳定环境跑完，接手时**建议先跑一次 `npm run test:text` 建基线**。

**用例 ID 约定**：`T*`（文本/Markdown 查看器）、`F*`（功能）、`S9*`（冒烟/媒体链路）、双栏与终端各自编号。
新增功能**必须补用例**并在 `devDocs/kuikly-app-features-test-plan.md` 登记。

**测试纪律（务必遵守，见 `AGENTS §3.1` 规则 7–12）**
- 按影响面选测，不默认全量；**改到的套件必须全绿**。
- 每步必须可超时/可止损，**绝不无限等待**；测试前后都要 kill 残留客户端（`pretest*`/`posttest*` 钩子已内置）。
- 需要外部资源：**网关**必须先在 `:18090` 跑起来（`cd electron && npm run gateway`），测试服务器 `192.168.2.2` 可达。
- 关键路径要 `Page.captureScreenshot` 留证（`electron/test/artifacts/`）。

**已知不稳定/未收敛项**
- `T7b`（点「应用」后正文加粗）历史上出现过失败：根因是**对已含 `**` 的内容重复包裹**，
  已加幂等修复，但**尚未在稳定环境跑出一次完整绿**，需复测。
- 大文档相关用例（虚拟列表）是近期改动，建议各跑一次 `test:text` 复核。
- macOS 视频"随窗口改变大小"仍是已知问题（见 `AGENTS §13.1.1` 备注）。

---

## 5. 关键文件索引

| 主题 | 文件 |
|---|---|
| SFTP 能力契约（commonMain） | `core/src/commonMain/kotlin/com/tencent/kuikly/core/module/sftp/*.kt` |
| 浏览页 / 首页 / 收藏 / 历史 / 设置 | `demo/src/commonMain/kotlin/com/tencent/kuikly/demo/pages/sftp/*.kt` |
| 查看器与编辑器 | `demo/.../sftp/viewer/**`（`SftpViewerDispatcherPage` 是入口分发） |
| Markdown 解析与图表 | `viewer/md/MarkdownParser.kt`、`viewer/md/MermaidFlowchart.kt`、`viewer/SftpDiagramView.kt` |
| 文档缓存 | `viewer/SftpDocCache.kt` |
| 缓存引擎 | `demo/.../sftp/cache/**` |
| 终端 | `demo/.../sftp/terminal/**` |
| 双栏 | `demo/.../sftp/FilesDualPanePage.kt` |
| Web 网关 | `sftp-gateway/server.js`（`/rpc` + 媒体 Range + 终端 spawn） |
| Electron 宿主 | `electron/main.js`（内嵌网关、独立窗口、localFs）、`electron/preload.js` |
| Android 原生 | `core-render-android/.../expand/module/{KRSftpClient,KRSftpModule,...}.kt` |
| iOS/macOS 原生 | `core-render-ios/Extension/Modules/KRSftp*.{h,m}` |
| HarmonyOS 原生 | `core-render-ohos/src/main/cpp/libohos_render/expand/modules/sftp/**` |
| 测试 | `electron/test/*.mjs`、`devDocs/*test-plan*.md` |
| 权威专题 | **`AGENTS.md §13`**（现状/构建矩阵/跨端规则/踩坑/测试机/端到端里程碑/文件索引） |

---

## 6. 下一步建议（按价值排序）

1. **复测并收敛测试**：先 `npm run test:text` 复核 Markdown 虚拟列表与 `T7b`；再按改动面跑对应套件。
2. **HarmonyOS 补齐**：本地 HTTP 代理（`KRLocalHttpProxy` 仍是 stub 且未进 `SOURCE_SET`）+ 视频组件 → 才能播放；
   运行时验证需要 OHOS 设备/模拟器镜像。
3. **查看器续做**（见 `devDocs/markdown-viewer-plan.md` 路线图）：宿主增强（mermaid.js / highlight.js 已有基础）、
   正文图片（走代理 URL）、KaTeX 子集、脚注、磁盘缓存。
4. **macOS 视频随窗口缩放**：修 `updateRootViewSize` 链路（`AGENTS §13.1.1` 已记录）。

---

## 7. 必须遵守的规则（摘要，详见 `AGENTS.md`）

- **AI 自主决策**（§2.5）：技术选型/实现/依赖/提交粒度/是否推送**由 AI 拍板**，不再逐项问用户；
  但必须**自己验证**、**如实汇报**（未运行时验证必须标注），并保留安全红线
  （不提交凭据、不做不可逆破坏操作、不擅改权限/签名/发布配置、不伪造验证结果）。
- **跨平台**（§3.1）：每个功能六端共用设计；重型 UI 必须有 commonMain 降级实现；未实现端显式不可用；禁止伪报成功。
- **提交**：Angular Convention（`feat/fix/docs/refactor/chore`），中文说明；改到哪测到哪。
- **`zhaojian` 分支的固定交付动作**（§2.5/§3.1 规则 11）：每次开发/修复收尾必须
  1. `cd electron && npm run dist:release`（或 `npm run dist`）→ 2. `~/Downloads` 自动留副本
  （命名 `Kuikly SFTP-<版本>-<yyyyMMdd-HHmmss>-<描述>.dmg`，**时间在前**便于排序）
  → 3. `npm run install:app` 覆盖安装到 `/Applications/Kuikly SFTP.app`。
  仅当用户明确说不用时才可跳过，且要在汇报里写明。
- **多工作树注意**：真正提交的是根仓库 `zhaojian` 分支；`.kilo/worktrees/*` 可能是旧状态。

---

## 8. 环境与常用命令

**环境**：Intel Mac；JDK 17（`corretto-17.0.13`，Gradle 7.6.3 不兼容 JDK 21+）；
代理 `http://127.0.0.1:7897`（下载/`pod install` 需要）；测试服务器 `192.168.2.2:22`（`zhaojian/zhaojian`，`/home/zhaojian`）。

```bash
# 网关（测试/Web 必须，端口 18090）
cd electron && npm run gateway

# 桌面端开发运行 / 打包 / 安装
cd electron && npm run start            # 开发态启动（含 sync）
cd electron && npm run build:web        # 仅重建 Web 产物（debug）
cd electron && npm run dist:release     # Release：web(production) + electron-builder + 存 Downloads
cd electron && npm run install:app      # 覆盖安装到 /Applications

# 测试（按影响面选跑；改到的必须全绿）
cd electron && npm test                 # smoke
cd electron && npm run test:text        # 文本/Markdown 查看器
cd electron && npm run test:features    # 收藏/历史/设置/缓存
cd electron && npm run test:dual        # 双栏
cd electron && npm run test:player      # 播放窗口
cd electron && npm run test:term        # 终端

# 其它端构建
./gradlew :androidApp:assembleDebug                                   # Android（JDK 17）
cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build
cd macApp && xcodebuild -workspace macApp.xcworkspace -scheme macApp -configuration Debug build
./2.0_ohos_demo_build.sh                                              # 鸿蒙业务 so（渲染器见 AGENTS §13.1.2）
./gradlew :demo:packLocalJsBundleDebug :h5App:jsBrowserDevelopmentWebpack :miniApp:jsMiniAppDevelopmentWebpack
```

**鸿蒙渲染器（含 SFTP C++）单独构建**（无设备时最快的验证方式，详见 `AGENTS §13.1.2`）：
用 DevEco 的 `ohos.toolchain.cmake` 直接构建 `core-render-ohos/src/main/cpp/CMakeLists.txt`
（`-DOHOS_ARCH=arm64-v8a`，`-Wno-unused-command-line-argument`），产物 `libkuikly.so`。
