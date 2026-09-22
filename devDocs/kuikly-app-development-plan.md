# Kuikly 跨端 SFTP 应用 —— 完整开发规划

> 目标：把当前「SFTP 客户端能力」推进为一个**完整、可交付的 Kuikly 跨端应用**
> （六端 + Electron 桌面壳），同时**不破坏跨端架构**。
>
> 关联文档：
> - `docs/SFTP-实现详解.md`（实现走读）
> - `docs/SFTP-Client.md`（设计与 spec）
> - `devDocs/sftp-development-progress.md`（进度快照）
> - `AGENTS.md` §13（现状矩阵 / 踩坑 / 维护规则）
>
> 主导原则：**任何新增能力都必须"六端同构 + 桌面壳只做宿主"**。
> 冲突时以 `AGENTS.md` 与 `openspec/config.yaml` 为准。

---

## 目录

- [0. 文档信息](#0-文档信息)
- [1. 目标、非目标与不变量](#1-目标非目标与不变量)
- [2. 现状盘点与差距](#2-现状盘点与差距)
- [3. 总体架构与边界契约](#3-总体架构与边界契约)
- [4. 里程碑总览](#4-里程碑总览)
- [5. 详细任务分解](#5-详细任务分解)
- [6. Electron 桌面壳专项设计](#6-electron-桌面壳专项设计)
- [7. 跨端一致性策略](#7-跨端一致性策略)
- [8. 质量与测试策略](#8-质量与测试策略)
- [9. 安全与合规](#9-安全与合规)
- [10. 发布与运维](#10-发布与运维)
- [11. 风险登记与缓解](#11-风险登记与缓解)
- [12. 决策记录（ADR）](#12-决策记录adr)
- [13. 任务清单与排期](#13-任务清单与排期)
- [14. 全局验收标准（DoD）](#14-全局验收标准dod)
- [15. 附录](#15-附录)

---

## 0. 文档信息

| 项 | 值 |
|---|---|
| 版本 | v1.0 |
| 范围 | SFTP 客户端应用（六端 + Electron 桌面壳）|
| 读者 | 客户端开发、跨端框架维护者、测试、发布 |
| 现状快照 | 2026-09（Android/iOS/macOS 可用；Web 经网关可用；HarmonyOS 核心可用无媒体；MiniApp 未验证）|
| 变更影响面 | **仅新增宿主与能力，不改 `core/`、`compose/`、各端 renderer 的既有契约** |

---

## 1. 目标、非目标与不变量

### 1.1 产品目标

交付一个**完整 Kuikly 跨端应用**，在下列形态上可用且体验一致：

1. Android（aar）
2. iOS（framework）
3. macOS（framework）
4. HarmonyOS（.so）
5. Web / H5（浏览器 + Node 网关）
6. 微信小程序（JS）
7. **桌面壳 Electron**（复用 Web 实现，可安装运行）

功能面：连接管理、文件浏览与操作、上传下载、批量、收藏、播放历史、流式播放、文档预览、设置（倍速等）、诊断与错误上报。

### 1.2 非目标

- ❌ 不修改 Kuikly 框架本体（`core/`、`compose/`、`core-render-*` 的公开契约）
- ❌ 不在 commonMain 引入平台分支（`if (isIOS)` / `if (isElectron)`）
- ❌ 不做服务端多租户 / 账号体系
- ❌ 不引入第二套 UI 技术栈（Electron 不得自绘业务 UI）

### 1.3 不变量（**红线，任何改动都不得破坏**）

| # | 不变量 | 校验方式 |
|---|---|---|
| I1 | `core/`、`compose/` 为纯 KMP，禁止依赖任何 `core-render-*` | `./gradlew :core:compileKotlin*` + 代码审查 |
| I2 | Compose 仅 `androidx.compose.runtime.*` 用官方包，其余 `com.tencent.kuikly.compose.*` | 代码审查 |
| I3 | 自研 DSL 与 Compose DSL 不混用 | 代码审查 |
| I4 | Module 桥约定：模块名 == 原生类名；入参/回参 JSON；二进制走原子通道 | e2e + 单端集成自测 |
| I5 | 桩实现必须显式失败（`9999`），绝不伪报成功 | 代码审查 + 集成自测 |
| I6 | 错误码跨端同一套语义（`SftpErrorCode`） | 各端 formatter 对照表 |
| I7 | Web/小程序不得假设有 TCP/SSH，必须经网关 | 代码审查 |
| I8 | **Electron 只做宿主**：启动网关、开窗口、加载 H5、注入端口；不写业务逻辑 | Electron 专项审查清单（§6.8）|
| I9 | `electron/` 独立依赖，不进入 Gradle/KMP 构建图 | `./gradlew` 各端产物与 `electron/` 无交叉依赖 |

---

## 2. 现状盘点与差距

### 2.1 六端能力矩阵（当前，真实可用性）

| 端 | 协议栈 | 媒体代理 | 播放 | 状态 |
|---|---|---|---|---|
| Android | JSch | NanoHTTPD | ExoPlayer | ✅ 可用（74/74 自测）|
| iOS | NMSSH(libssh2) | GCDWebServer | AVPlayer | ✅ 可用（74/74）|
| macOS | 同 iOS | GCDWebServer | VLCKit | ✅ 可用 |
| HarmonyOS | libssh2(vendored) | ❌ 桩 | ❌ 无组件 | ⚠️ 核心可用、媒体缺失 |
| Web (H5) | Node 网关(ssh2) | 网关出 Range | `<video>` | ✅ 可用（自动化 21/21）|
| MiniApp | 复用 Web JS | 需网关 | — | ➖ 未验证 |
| **Electron** | — | — | — | ❌ 未开始 |

### 2.2 已具备的基础（可直接复用，不要在规划里重造）

- 共享能力层：`core/.../module/sftp/{SftpModule,SftpConnectionModule,SftpFavoritesModule,SftpPlaybackHistoryModule,SftpMediaProxyModule,SftpModel,SftpErrorCode,MimeExtMap,EncodingDetector,SftpMediaUrlBuilder,I18n}.kt`
- 业务/UI：`demo/.../pages/sftp/`（首页/浏览/播放/属性/收藏/历史/预览分发/预览器/主题/无障碍）
- Web 网关：`sftp-gateway/server.js`（19 个 sftp 方法 + mediaProxy + 持久化）
- Web 模块：`h5App/.../module/SftpGatewayModules.kt` + 宿主注册
- Web 视频组件：`core-render-web/base/.../KRVideoView.kt`（含 `setFullscreen`、`buffered`）
- 播放控件：`SftpPlayerPage` + `SftpPlayerTokens`（mpv OSC 风格两行布局）
- 自动化测试：`sftp-gateway/test/sftp-web.test.js`（A1-A12 + B1-B8）、`scripts/e2e.sh`
- 集成自测页：`SftpIntegrationTestPage`（74 项，含字节级校验）

### 2.3 差距清单（本规划要关闭的 gap）

| 编号 | 差距 | 影响 | 归属里程碑 |
|---|---|---|---|
| G1 | 未做 known_hosts 校验（P0 安全）| MITM 风险 | M1 |
| G2 | 凭据明文落盘（SharedPreferences / NSUserDefaults / JSON）| 泄露风险 | M1 |
| G3 | 本地代理/网关绑定不可控（曾绑全网卡）| 局域网暴露 | M1 |
| G4 | 预览器 Markdown/HTML/PDF 为占位 | 功能不完整 | M2 |
| G5 | 播放器缺 缓冲条(原生)/缩略图/PiP/字幕/音量滑杆 | 体验 | M2 |
| G6 | 网关会话在内存，重启即失效 | 体验/稳定性 | M3 |
| G7 | **无桌面可安装形态** | 交付形态缺失 | M4/M5 |
| G8 | HarmonyOS 无媒体代理与播放器 | 端能力缺口 | M6 |
| G9 | MiniApp 未做运行时验证 | 端能力未证实 | M7 |
| G10 | 无签名/公证/更新/崩溃上报 | 无法正式发布 | M8 |

---

## 3. 总体架构与边界契约

### 3.1 分层（含 Electron 桌面壳）

```
┌──────────────────────────────────────────────────────────────────────┐
│ L1 业务与 UI（100% 共享，commonMain）                                 │
│   demo/.../pages/sftp/*  + theme/* + viewer/*                         │
├──────────────────────────────────────────────────────────────────────┤
│ L2 能力声明（100% 共享）                                              │
│   core/.../module/sftp/*（5 个 Module + 模型 + 错误码 + 工具）          │
├──────────────────────────────────────────────────────────────────────┤
│ L3 桥接层（各端实现同名类 / 同名 JS 模块）                             │
│   Android=KRSftp*.kt  iOS/macOS=KRSftp*.m  OHOS=KRSftp*.cpp           │
│   Web=h5App/.../SftpGatewayModules.kt                                  │
├──────────────────────────────────────────────────────────────────────┤
│ L4 平台实现层                                                          │
│   SSH/SFTP：JSch / NMSSH / libssh2 / Node ssh2(网关)                   │
│   媒体代理：NanoHTTPD / GCDWebServer / (OHOS 待做) / 网关直出 Range     │
│   播放器：ExoPlayer / AVPlayer / VLCKit / <video>                      │
├──────────────────────────────────────────────────────────────────────┤
│ L5 宿主（本规划新增 Electron）                                         │
│   Electron main(Node)：启动 sftp-gateway（随机端口）+ 创建窗口          │
│   Electron renderer    ：加载既有 H5 产物（零改动）                     │
│   ── 只做宿主，不含业务逻辑 ──                                          │
└──────────────────────────────────────────────────────────────────────┘
```

### 3.2 交付形态与分发渠道

| 形态 | 渠道 | 产物 |
|---|---|---|
| Android | Maven | aar / apk（宿主）|
| iOS | CocoaPods / SPM | framework / ipa |
| macOS | CocoaPods | .app（原生 VLCKit）|
| HarmonyOS | ohpm（渲染器）| .so / hap |
| Web | npm / CDN / 静态托管 | `nativevue2.js` + 壳 |
| MiniApp | 微信平台 | 代码包 |
| **Electron** | GitHub Releases / 官网 | `.dmg` / `.exe`(nsis) / `.AppImage` |

### 3.3 Electron 边界契约（**必须逐条遵守**）

**允许**
1. 启动/托管 `sftp-gateway`（同进程 `require` 或 `utilityProcess.fork`）
2. 创建 `BrowserWindow` 并加载既有 H5 产物（`index.html?page_name=...`）
3. 把网关地址（`window.__SFTP_GATEWAY_URL__`）注入渲染层
4. 提供桌面专属能力（菜单/托盘/文件另存为/通知/深链），**通过 IPC + preload 暴露**
5. 打包、签名、公证、自动更新

**禁止**
1. 在 Electron 里重写任何 SFTP/文件/播放业务逻辑
2. 在 commonMain 写 `if (isElectron)` 之类的平台分支
3. 让 `electron/` 成为任何 KMP 端构建的依赖
4. 复制一份 `sftp-gateway`（必须复用同一份）
5. 在渲染层开启 `nodeIntegration`

### 3.4 目录规划

```
新增（隔离，不影响既有构建）
  electron/
    package.json            # 独立依赖
    electron-builder.yml    # 打包配置
    main.js                 # 宿主主进程
    preload.js              # 安全桥（仅暴露必要 API）
    scripts/sync-resources.mjs  # Gradle 产物 → electron/resources
    resources/              # 由 sync 生成（git 忽略，除 .gitkeep）
    test/smoke.mjs          # 桌面冒烟测试
  devDocs/kuikly-app-development-plan.md  # 本文档

复用（不改或小改）
  sftp-gateway/             # 网关（唯一一份）
  h5App/, demo/, core/, core-render-*/

禁止改动
  core/、compose/ 的对外契约；任何平台的既有注册与构建脚本（除必要修复）
```

---

## 4. 里程碑总览

| 里程碑 | 主题 | 关键产出 | 退出标准 |
|---|---|---|---|
| **M0** | 基线与守卫 | 不变量清单、CI 加固、测试基线 | 六端可构建；`npm test` 绿；不变量检查脚本落地 |
| **M1** | 安全加固（P0） | host key 校验、凭据加密、代理绑定回环 | 三端+Web 均不再"无条件接受指纹"；凭据加密；绑 127.0.0.1 |
| **M2** | 体验收尾 | 预览器（MD/HTML/PDF）、播放器补全 | 各端 74+ 项自测通过；预览器可用 |
| **M3** | 网关增强 | 会话持久/重连、鉴权、限流 | 网关重启不影响已打开页面；未授权请求被拒 |
| **M4** | Electron MVP | 可双击运行、自动起网关 | 打包出 .app 并能打开 SFTP 首页并播放 |
| **M5** | Electron 完整 | 桌面能力 + 三平台打包 + 更新 | dmg/nsis/AppImage 产出，自动更新可用 |
| **M6** | HarmonyOS 媒体 | KRLocalHttpProxy 真实现 + 播放器 | OHOS 可播放 SFTP 视频（或明确记录不支持）|
| **M7** | MiniApp 验证 | 运行时验证 + 域名白名单 | 小程序内可浏览/播放（或明确记录限制）|
| **M8** | 发布与运维 | 签名公证、崩溃上报、发布流程 | 正式包可分发；有回滚方案 |

> 依赖关系：M0 → M1 → M2 → M3 → M4 → M5；M6/M7 可与 M4/M5 并行。

---

## 5. 详细任务分解

> 每个任务给出：背景 / 改动文件 / 步骤 / 验证 / DoD。

### M0 基线与守卫

**M0-1 不变量检查脚本**
- 背景：跨端最怕"悄悄引入平台分支/依赖倒置"。
- 改动：新增 `scripts/check-invariants.sh`（仓库根）
- 步骤：
  1. 检查 `core/`、`compose/` 是否 import `core.render.android|ios|ohos|web`
  2. 检查 commonMain 是否出现 `android.` / `UIKit` / `isElectron`
  3. 检查 `electron/` 是否被任何 `*.gradle.kts` 引用
  4. 检查 `compose/` 是否使用 `androidx.compose.foundation`
- 验证：`bash scripts/check-invariants.sh` 本地通过；接入 CI。
- DoD：CI 上以非零退出阻断违规。

**M0-2 测试基线**
- `npm run test:rpc`、`npm test`、`npm run e2e` 纳入 CI；文档化 `HEADLESS=0`/`SLOWMO`。
- 记录「已知测试差异」（headless 下 pan 不稳 → 拖动仅 HEADLESS=0 断言）。

**M0-3 六端构建基线**
- 固化命令（见 §15.1），CI 至少跑 Web + Android（其它端按周期跑）。

### M1 安全加固（P0）

**M1-1 host key 校验（TOFU + known_hosts）**
- 背景：三端与网关均未校验主机指纹（审计 P0）。
- 改动：
  - `core/.../module/sftp/SftpModel.kt`：`knownHosts` 已声明，补齐语义；新增 `SftpHostKeyInfo`
  - 各端实现：
    - Android `KRSftpClient.kt`：`JSch.setKnownHosts()`，首次 TOFU 落库
    - iOS/macOS `KRSftpSession.m`：`session.fingerprint` 校验 + 存储
    - OHOS `KRSftpSession.cpp`：`libssh2_hostkey_hash` 校验
    - Web `sftp-gateway/server.js`：`hostVerifier` 回调 + `data/known_hosts.json`
  - UI：`SftpConnectEditPage` 首次连接弹指纹确认；不一致时用 `1004/1005` 阻断
- 验证：错误指纹的测试服务器（可用 SSH 端口转发伪造）应被阻断；正确指纹允许。
- DoD：四端（A/iOS/mac/Web）都能阻断指纹不匹配；首次确认后二次连接不再弹窗。

**M1-2 凭据加密存储**
- Android：`EncryptedSharedPreferences`（`androidx.security:security-crypto`）
- iOS/macOS：Keychain
- OHOS：`@ohos.security.huks` 或加密后落文件
- Web/Electron：网关侧加密或明确提示"浏览器本地存储不加密"
- DoD：静态检查（导出 prefs/keychain dump）不再出现明文口令。

**M1-3 代理绑定回环**
- Android `LocalHttpProxyServer`：绑定 `127.0.0.1`；iOS `GCDWebServer`：`GCDWebServerOption_BindToLocalhost = @YES`
- 网关：`server.listen(PORT, '127.0.0.1')`（已满足），并支持随机端口
- DoD：`lsof`/`netstat` 确认仅回环监听。

### M2 体验收尾

**M2-1 预览器落地**
- Markdown：接入轻量渲染（或自研基础子集）
- HTML：Web 用 iframe（经网关 URL）；原生用 WebView/TextView
- PDF：原生用系统 PDF 组件；Web 用 `<embed>`/pdf.js
- DoD：四类文件在至少 3 端可读；不支持端显式提示（I5）。

**M2-2 播放器补全**
- 缓冲条原生端：`VideoView` 增加 buffered 回传（Android/iOS/OHOS）
- 音量滑杆：`VideoAttr.volume(Float)`（各端实现；Web 直接 `<video>.volume`）
- 倍速菜单已具备；补"记忆倍速"（localStorage/UserDefaults/SharedPreferences）
- DoD：各端控件一致；断网/弱网下缓冲条推进。

### M3 网关增强

- 会话持久化到磁盘（`data/sessions.json`，仅存元数据，重连时重建）
- `POST /rpc` 增加 **一次性令牌**（主进程注入），拒绝非本机/非令牌请求
- 限流：单 token 并发 Range 上限；退避与超时
- DoD：网关重启后页面可"重连"恢复；未授权请求 401/403。

### M4 Electron MVP（详见 §6）

**M4-1 脚手架**：`electron/package.json`、`main.js`、`preload.js`
**M4-2 网关托管**：主进程内 `require('../sftp-gateway/server.js')`（读 `SFTP_GATEWAY_PORT`，或随机端口）
**M4-3 产物同步**：`scripts/sync-resources.mjs`（Gradle 产物 → `electron/resources`）
**M4-4 加载页面**：`loadFile('resources/index.html', { query: { page_name: 'SftpHomePage' } })`，注入 `__SFTP_GATEWAY_URL__`
**M4-5 冒烟测试**：`test/smoke.mjs`（CDP attach 校验首页渲染 + 播放）
- DoD：`npm run start:electron` 可打开应用；`npm run dist` 出 .app；冒烟测试绿。

### M5 Electron 完整

- 桌面能力：菜单、托盘、最小化、文件另存为（下载走 `dialog.showSaveDialog`）、系统通知
- 单实例锁、深链（`kuikly-sftp://`）
- 三平台打包（dmg/nsis/AppImage）、图标、签名、公证（macOS）
- 自动更新（`electron-updater` + GitHub Releases）
- DoD：三平台安装包可安装运行；更新通道可用；崩溃有日志。

### M6 HarmonyOS 媒体链路

- 实现 `KRLocalHttpProxy`（libmicrohttpd 或自研 tiny http），并**加入 CMake `SOURCE_SET`**
- 注册 `KRLocalMediaProxyModule`（与 Web/原生同名）
- 视频组件：接入系统 `AVPlayer`（`@ohos.multimedia.media`）或自研 `KRVideoView`
- DoD：OHOS 可播放 SFTP 视频；否则在 spec/AGENTS 显式记录不支持（I5）。

### M7 MiniApp 验证

- 起网关并让小程序可访问（域名白名单 + HTTPS 或本地调试）
- 复用 Web JS 模块，验证：连接/浏览/播放
- DoD：小程序内跑通核心链路，或记录限制与替代方案。

### M8 发布与运维

- 版本策略、产物命名、Release Notes 模板
- 崩溃与诊断：`diagnostics.log`（macOS 已有）+ 各端 KLog 汇聚；可选 Sentry
- 回滚：保留上一版本安装包与配置兼容说明
- DoD：一次完整的发布演练（打标签 → 出包 → 分发 → 升级 → 回滚）。

---

## 6. Electron 桌面壳专项设计

### 6.1 进程模型

```
┌─ Electron main (Node) ────────────────────────────────┐
│  app.whenReady()                                      │
│   ├─ 启动 sftp-gateway（utilityProcess 或 require）    │
│   ├─ 记录实际端口 → 注入到窗口                         │
│   ├─ 注册菜单/托盘/IPC 处理器                          │
│   └─ new BrowserWindow(preload)                        │
├─ preload (contextBridge) ─────────────────────────────┤
│  暴露：window.kuiklyHost = { saveFile, notify, ... }   │
│  暴露：window.__SFTP_GATEWAY_URL__ = 'http://127.0.0.1:<port>' │
├─ renderer (Chromium) ─────────────────────────────────┤
│  加载 resources/index.html + nativevue2.js + h5App.js   │
│  业务与 UI 100% 复用 H5（零改动）                       │
└────────────────────────────────────────────────────────┘
```

### 6.2 网关托管（关键：随机端口 + 注入）

`main.js` 骨架：
```js
const { app, BrowserWindow, utilityProcess, ipcMain, dialog } = require('electron');
const path = require('path');

let gatewayPort = 0;
function startGateway() {
  return new Promise((resolve, reject) => {
    // 让网关支持 SFTP_GATEWAY_PORT=0 → 由 OS 分配，网关回传实际端口
    const child = utilityProcess.fork(
      path.join(__dirname, '..', 'sftp-gateway', 'server.js'),
      [], { env: { ...process.env, SFTP_GATEWAY_PORT: '0', SFTP_GATEWAY_REPORT: '1' } }
    );
    child.on('message', (msg) => { if (msg && msg.type === 'listening') resolve(msg.port); });
    child.on('exit', (code) => { /* 网关异常退出 → 提示并可选重启 */ });
    setTimeout(() => reject(new Error('gateway start timeout')), 15000);
  });
}

app.whenReady().then(async () => {
  gatewayPort = await startGateway();
  const win = new BrowserWindow({
    width: 1180, height: 820,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  await win.loadFile(path.join(__dirname, 'resources', 'index.html'),
    { query: { page_name: 'SftpHomePage' } });
});
```
> 网关需小改：`server.listen(0)` 时通过 `process.parentPort?.postMessage({type:'listening', port})`（utilityProcess）或 stdout 约定回传端口。**这是唯一允许的网关改动**，且不改变 `/rpc` 契约。

### 6.3 产物同步链路

```
Gradle                                         electron/
  :demo:packLocalJsBundleDebug  ─┐
  :h5App:jsBrowserDevelopmentWebpack ─┴─► scripts/sync-resources.mjs
                                            ├─ resources/nativevue2.js   (解压 zip)
                                            ├─ resources/h5App.js
                                            ├─ resources/index.html
                                            └─ resources/assets/
```
`package.json`：
```json
{
  "scripts": {
    "sync": "node scripts/sync-resources.mjs",
    "build:web": "cd .. && ./gradlew :demo:packLocalJsBundleDebug -Pkuikly.useLocalKsp=false && ./gradlew :h5App:jsBrowserDevelopmentWebpack",
    "start": "npm run sync && electron .",
    "dist": "npm run sync && electron-builder",
    "test": "node test/smoke.mjs"
  }
}
```

### 6.4 IPC 契约（桌面能力，仅暴露必要项）

| 通道 | 方向 | 用途 | 说明 |
|---|---|---|---|
| `host:saveFile` | renderer→main | 另存为（下载/导出）| 主进程弹 `showSaveDialog` 并写盘 |
| `host:notify` | renderer→main | 系统通知 | 下载完成/播放结束 |
| `host:openExternal` | renderer→main | 打开外部链接 | 白名单校验 |
| `host:getInfo` | renderer→main | 版本/平台/网关端口 | 只读 |
| `host:onDeepLink` | main→renderer | 深链（`kuikly-sftp://…`）| 单实例 |

**契约纪律**：页面侧通过一个**新增的 host module**（如 `KRSftpHostModule`）使用这些能力，
不在页面里直接 `window.kuiklyHost.xxx`：
```
commonMain: KRSftpHostModule.saveFile(...) → 各端 impl
  - Web/Electron: 走 preload 暴露的 window.kuiklyHost
  - Android/iOS/OHOS: 走各自原生（分享/存沙盒/通知）
  - 不支持 → 显式 9999
```
这样 Electron 的能力**也走 Module 体系**，六端保持同构，且桌面专属能力不会渗进 commonMain。

### 6.5 安全基线（Electron）

- `contextIsolation: true`、`nodeIntegration: false`、`sandbox: true`
- `preload` 只暴露白名单 API；不暴露 `ipcRenderer` 本体
- `webSecurity` 保持默认开启；CSP 收紧（仅允许本地资源 + `http://127.0.0.1:*`）
- 禁用 `remote` 模块；`will-navigate` / `setWindowOpenHandler` 拦截外部导航
- 网关仅绑 `127.0.0.1` + 一次性令牌（§M3）

### 6.6 打包与分发

- `electron-builder.yml`：`appId`、`productName`、`mac.target=dmg`、`win.target=nsis`、`linux.target=AppImage`
- macOS：`hardenedRuntime`、`entitlements`、`notarize`（需 Apple Developer）
- Windows：代码签名证书（可选但建议）
- 产物命名：`KuiklySFTP-<version>-<os>-<arch>.<ext>`

### 6.7 与既有 `macApp` 的关系（决策点）

| 方案 | 说明 |
|---|---|
| A. 并存 | macApp（原生 VLCKit，体积小、系统集成好）+ Electron（跨平台一致） |
| B. Electron 取代 macApp | 统一桌面实现，少维护一套，但失去原生播放器与更小体积 |
| C. 仅内部用 Electron | 对外仍发 macApp |

**建议**：短期 A（并存），把 Electron 定位为"跨平台桌面交付 + 快速验证"；是否取代由用户体验/体积数据决定（记入 ADR-02）。

### 6.8 Electron 不影响跨端的"反模式清单"（Review 必查）

- ❌ `electron/` 出现在任何 `*.gradle.kts` 或 `settings.*.gradle.kts`
- ❌ `core/` 或 `demo/` commonMain import electron/Node 相关符号
- ❌ 页面里出现 `window.kuiklyHost` 直接调用（必须经 host module）
- ❌ 复制 `sftp-gateway` 到 `electron/`
- ❌ 为 Electron 在 commonMain 增加 `expect/actual`
- ✅ 唯一允许的共性改动：网关支持随机端口与端口回传（不改 `/rpc` 契约）

---

## 7. 跨端一致性策略

### 7.1 新增能力一律走 Module（六端同构）

新增能力（含桌面专属）必须按 §13.2 的**五步**落地：
1. `core/.../module/sftp/XxxModule.kt` 声明方法
2. `ModuleConst.kt` 加常量
3. 各端实现同名类/同名 JS 模块（Android/iOS/macOS/OHOS/Web）
4. 页面 `acquireModule` + `createExternalModules()` 注册
5. Web 端在 `SftpGatewayModules.kt` + 网关加方法

### 7.2 平台能力矩阵登记制

任何"某端有、某端没有"的能力，必须在 `AGENTS.md` §13 的能力矩阵里显式登记（含不支持时的错误码 `9999` 与 UI 提示），禁止静默降级。

### 7.3 每端验证脚本

| 端 | 验证方式 |
|---|---|
| Android | `:androidApp:assembleDebug` + 模拟器 `SftpIntegrationTestPage` |
| iOS | `xcodebuild` + 模拟器 74/74 |
| macOS | `xcodebuild` + 界面操控 |
| OHOS | CMake/构建脚本 + 真机 |
| Web | `npm test` / `npm run e2e` |
| Electron | `test/smoke.mjs`（CDP attach）|
| MiniApp | 微信开发者工具 + 真机 |

---

## 8. 质量与测试策略

### 8.1 测试分层

| 层 | 范围 | 现状 | 目标 |
|---|---|---|---|
| L1 单元 | 纯函数（MimeExtMap/EncodingDetector/UrlBuilder/错误映射）| 部分 | 补齐 commonTest |
| L2 集成 | 各端 74 项 | A/iOS/mac ✅ | OHOS/MiniApp 补齐 |
| L3 E2E-Web | 网关 + 浏览器 | 21/21 ✅ | 增补网关异常/鉴权用例 |
| L4 E2E-Electron | 桌面壳 | ❌ | `test/smoke.mjs` |
| L5 兼容 | Electron Chromium 差异 | ❌ | 用 Electron 回归 H5 |

### 8.2 Electron 冒烟测试要点

- 启动后：首页出现「SFTP 客户端」
- 连接测试机 → 浏览出现 `sftp_kuikly_media.mp4`
- 播放：时间推进
- 桌面能力：`host:saveFile` 走通（可用临时目录）
- 退出：网关子进程被回收（无残留进程）

### 8.3 回归清单（每次发布前）

- [ ] `bash scripts/check-invariants.sh`
- [ ] `cd sftp-gateway && npm run test:rpc && npm test`
- [ ] `HEADLESS=0 SLOWMO=200 npm test`（含拖动）
- [ ] 至少 3 端集成自测 + Electron 冒烟
- [ ] 更新 `devDocs/sftp-development-progress.md`

---

## 9. 安全与合规

| 项 | 现状 | 目标 | 里程碑 |
|---|---|---|---|
| host key 校验 | ❌ 无条件接受 | TOFU + known_hosts + UI 确认 | M1 |
| 凭据存储 | 明文 | 加密（Keychain/EncryptedSharedPreferences/Huks）| M1 |
| 代理绑定 | 曾全网卡 | 仅 127.0.0.1 | M1 |
| 网关鉴权 | 无 | 一次性令牌 + 回环 | M3 |
| Electron 安全 | — | contextIsolation/sandbox/CSP | M4 |
| 日志脱敏 | token 有 `safeUrlForLog` | 全链路审计 | M2 |
| 依赖审计 | 未做 | `npm audit` / Gradle 依赖检查 | M8 |

---

## 10. 发布与运维

- **版本**：SemVer；`major` 破坏兼容、`minor` 能力、`patch` 修复
- **渠道**：Android→Maven；iOS/macOS→CocoaPods；OHOS→ohpm；Web→npm/CDN；Electron→Releases
- **更新**：Electron 用 `electron-updater`（差分/全量）；原生端走各商店
- **诊断**：统一 `diagnostics.log`（JSONL）+ 各端 KLog；tag 约定沿用 `AGENTS.md` §13.5
- **回滚**：保留上一版安装包与"配置向后兼容"说明

---

## 11. 风险登记与缓解

| # | 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|---|
| R1 | Electron 渗入业务，破坏跨端 | 中 | 高 | §6.8 反模式清单 + Review 门禁 |
| R2 | 桌面/浏览器 Chromium 版本差异导致 H5 异常 | 中 | 中 | L5 兼容回归；锁定 Electron 版本 |
| R3 | host key 校验引入兼容问题（老用户）| 中 | 中 | TOFU 默认放行一次 + 可导出 known_hosts |
| R4 | 网关随机端口 + 多实例冲突 | 低 | 中 | `listen(0)` + 端口回传；单实例锁 |
| R5 | OHOS 媒体实现成本高 | 高 | 中 | 先记录不支持（I5），排后做 |
| R6 | 小程序网关网络不可达 | 高 | 中 | 明确限制 + 网关部署指引 |
| R7 | 打包签名链条复杂 | 中 | 中 | M8 专项演练，CI 复用密钥 |
| R8 | 测试 flaky（headless pan）| 中 | 低 | 已隔离：拖动仅 HEADLESS=0 |

---

## 12. 决策记录（ADR）

- **ADR-01 Electron vs Tauri**：选 Electron（零改动复用 Node 网关 + H5 产物；Tauri 需 Rust/sidecar）。若体积敏感再评估 Tauri。
- **ADR-02 桌面壳与 macApp**：短期并存（§6.7-A）。
- **ADR-03 网关鉴权**：回环 + 一次性令牌（不引入账号体系）。
- **ADR-04 端口**：Electron 内 `listen(0)` 随机端口；开发保持 18090。

> 新决策请按 `openspec/config.yaml` 规则补充 ADR 并回链本文档。

---

## 13. 任务清单与排期

> 人日估算基于熟悉本仓库的 1 名工程师；并行度可压缩工期。

| 里程碑 | 任务 | 人日 | 依赖 | 验收 |
|---|---|---|---|---|
| M0 | 不变量脚本 + CI | 1 | — | CI 阻断违规 |
| M0 | 测试基线固化 | 0.5 | — | 命令与文档一致 |
| M1 | host key（4 端）| 5 | M0 | 指纹不匹配被阻断 |
| M1 | 凭据加密（4 端）| 4 | — | 无明文 |
| M1 | 代理绑回环（3 端）| 1 | — | 仅回环监听 |
| M2 | 预览器 MD/HTML/PDF | 5 | — | 3 端可读 |
| M2 | 播放器补全（buffered/volume/记忆倍速）| 4 | — | 各端一致 |
| M3 | 网关会话/鉴权/限流 | 3 | M1 | 重启可恢复 |
| M4 | Electron 脚手架 + 托管 + 同步 + 冒烟 | 4 | M3 | 可运行 + 冒烟绿 |
| M5 | 桌面能力 + 三平台打包 + 更新 | 6 | M4 | 三平台安装包 |
| M6 | OHOS 代理 + 播放器 | 8 | M1 | 可播放或显式不支持 |
| M7 | MiniApp 验证 | 3 | M3 | 核心链路通 |
| M8 | 签名/公证/上报/发布演练 | 5 | M5 | 发布+回滚演练 |

**建议节奏**：M0→M1（2 周）→ M2（2 周）→ M3→M4（1.5 周）→ M5（1.5 周）→ M6/M7（并行 2 周）→ M8（1 周）。

---

## 14. 全局验收标准（DoD）

一个里程碑/任务"完成"必须同时满足：

1. **功能**：按任务描述可用；不支持端显式失败（`9999`）并有 UI 提示
2. **跨端**：`scripts/check-invariants.sh` 通过；能力矩阵已更新
3. **测试**：相关层测试通过（L1/L2/L3/L4 按范围）；Electron 改动需冒烟绿
4. **文档**：`AGENTS.md` §13 与 `devDocs/sftp-development-progress.md` 同步；新增专题文档登记 §11
5. **安全**：不引入明文凭据、不放宽监听范围、新增依赖经审计
6. **可发布**：产物可构建；Electron 改动可出包

---

## 15. 附录

### 15.1 命令速查

```bash
# 不变量检查
bash scripts/check-invariants.sh

# Web 产物
./gradlew :demo:packLocalJsBundleDebug -Pkuikly.useLocalKsp=false   # 需 JDK 17
./gradlew :h5App:jsBrowserDevelopmentWebpack

# 网关 + 测试
cd sftp-gateway
npm install
npm run test:rpc          # 只测网关
npm test                  # 全量（无头）
HEADLESS=0 SLOWMO=200 npm test   # 有头，可观看
npm run e2e               # 全自动编排（构建+起服务+测试+清理）

# Electron（M4 之后）
cd electron
npm install
npm run build:web && npm run start   # 开发运行
npm run dist                          # 打包

# 各端构建见 AGENTS.md §13.1.2
```

### 15.2 关键文件索引

| 主题 | 路径 |
|---|---|
| 共享能力 | `core/src/commonMain/.../module/sftp/` |
| 业务/UI | `demo/src/commonMain/.../pages/sftp/` |
| 播放控件 token | `demo/.../pages/sftp/SftpPlayerTokens.kt`（mpv OSC）|
| Web 网关 | `sftp-gateway/server.js` |
| Web 模块 | `h5App/src/jsMain/kotlin/module/SftpGatewayModules.kt` |
| Web 视频 | `core-render-web/base/.../KRVideoView.kt` |
| 测试 | `sftp-gateway/test/sftp-web.test.js`、`scripts/e2e.sh` |
| Electron（规划）| `electron/`（M4 创建）|

### 15.3 反模式清单（红线）

- 在 commonMain 写平台分支或 `isElectron`
- Electron 实现业务逻辑 / 复制网关
- 以"临时"为名放宽 `contextIsolation`/监听范围
- 未登记的平台差异（静默降级）
- 让 `electron/` 进入 Gradle 构建图

### 15.4 维护规则

- 本文档随里程碑推进更新；每完成一个 M 更新 §2 现状与 §13 勾选
- 与 `AGENTS.md` §13 双向同步；新增专题文档在 `AGENTS.md` §11 登记
- 重大架构决策补 ADR 并回链本文档

---

> 本文档为规划基线，具体实现以代码与 `AGENTS.md`/`openspec/config.yaml` 为准。
