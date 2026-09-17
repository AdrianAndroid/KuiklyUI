# SFTP 客户端 — 全平台功能开发计划

> 本文件是 `docs/SFTP-Client.md`（§1~§22 设计）的实施落地清单。按 Phase 0~6 顺序开发，每 Phase 完成即可独立验证（V0~V7 对应 §22 验证计划）。
>
> **本次执行策略**：先全平台开发功能（不构建），按「commonMain 共享层 → 各端原生 → 注册 + 入口」顺序，所有代码以可编译为目标的完整性写完，构建验证留到全部代码就绪后统一做。

## 总体文件清单（按 Phase 分层）

```
Phase 0 — commonMain 共享层（所有端的 Kotlin 逻辑，零原生依赖）  ~22 文件
Phase 1 — Android 原生层（Java/Kotlin）                          ~6 文件
Phase 2 — iOS/macOS 原生层（ObjC）                                ~8 文件
Phase 3 — HarmonyOS 原生层（C++/NAPI）                            ~6 文件
Phase 4 — Web/MiniApp 适配层（Kotlin/JS）                         ~3 文件（可选，SFTP 受沙箱限制）
Phase 5 — 宿主 App 注册 + 入口按钮                                ~5 文件
Phase 6 — 单元测试                                                ~3 文件
```

---

## Phase 0 — commonMain 共享层（最高优先，零原生依赖）

> 全部在 `core/src/commonMain/kotlin/com/tencent/kuikly/core/module/sftp/` 与 `demo/src/commonMain/kotlin/com/tencent/kuikly/demo/pages/sftp/`，全部用 JDK 即可编译。

### 0.1 数据模型层（`core/.../module/sftp/`）

| # | 文件 | 内容 | 行数估 |
|---|------|------|-------|
| 1 | `SftpModel.kt` | `SftpConnectParam`、`SftpEntry`、`SftpFavorite`、`SftpBatchTask`、`SftpPlaybackRecord`、`SftpCopyResult`、`OverwriteMode`、`AuthMethod`、`SftpFavoriteIcon`、`SftpFavoriteSortBy`、`SortOrder` | ~150 |
| 2 | `SftpErrorCode.kt` | `SftpErrorCode` 枚举（1001-9999 分段）+ `SftpConnectError` 枚举 + `SftpError` 解析工具 | ~120 |
| 3 | `SftpModule.kt` | `SftpModule : Module()` — `connect/disconnect/list/stat/openRead/read/close/download/upload/mkdir/rm/rename/move/copy/chmod/chown/setMtime/batchTask` 全部方法，走 `asyncToNativeMethod` | ~280 |
| 4 | `SftpFavoritesModule.kt` | `SftpFavoritesModule : Module()` — `add/remove/removeByConnection/list/isFavorited/update/search` | ~150 |
| 5 | `SftpPlaybackHistoryModule.kt` | `SftpPlaybackHistoryModule : Module()` — `upsert/get/listByDirectory/listByConnection/remove/clearByConnection/markCompleted` | ~150 |
| 6 | `LocalMediaProxyApi.kt` | `expect class` 声明 `startOrGetPort/registerToken/unregisterToken/stop`，每端 `actual` | ~40 |
| 7 | `MimeExtMap.kt` | 扩展名→MIME 集中映射表（§21.2.11） | ~80 |
| 8 | `EncodingDetector.kt` | UTF-8 BOM / GBK / Latin-1 嗅探（§21.6.2） | ~60 |
| 9 | `SftpMediaUrlBuilder.kt` | `http://127.0.0.1:<port>/<token>/<name>` 构建 + Range 解析工具 | ~50 |
| 10 | `I18n.kt` | `messageKey` → 中英文映射表（§21.8.9） | ~100 |

### 0.2 ModuleConst 扩展

| # | 文件 | 改动 |
|---|------|------|
| 11 | `core/.../module/ModuleConst.kt` | 加 `SFTP = "KRSftpModule"` / `SFTP_FAVORITES = "KRSftpFavoritesModule"` / `SFTP_PLAYBACK_HISTORY = "KRSftpPlaybackHistoryModule"` |

### 0.3 SFTP UI 页面（自研 DSL，`demo/.../pages/sftp/`）

| # | 文件 | 内容 | 行数估 |
|---|------|------|-------|
| 12 | `base/SftpBasePager.kt` | 抽象基类，提供 `sftpModule/sftpFavoritesModule/sftpPlaybackHistoryModule` accessor + 通用 loading/empty/error 三态 | ~80 |
| 13 | `SftpHomePage.kt` | 连接列表 + 新建/编辑/删除 + 测试连接 + 收藏 Tab + 历史 Tab（§17.3.1） | ~250 |
| 14 | `SftpBrowserPage.kt` | 目录浏览 + 排序 + 过滤 + 多选 + 心心收藏 + 面包屑 + 三态（§17.3.2） | ~320 |
| 15 | `SftpPlayerPage.kt` | 视频播放 + 续播提示 + 选集抽屉 + 自动下一集倒计时 + 首帧超时 + 纯音频分支（§17.3.3 + §20） | ~380 |
| 16 | `SftpFavoritesPage.kt` | 收藏列表 + 排序 + 失效检测 + 删除（§17.3.4） | ~200 |
| 17 | `SftpHistoryPage.kt` | 播放历史列表 + 清空 + 删除单条（§20.9） | ~200 |
| 18 | `SftpFilePropsPage.kt` | 文件属性展示 + chmod/chown/setMtime 编辑（§17.3.5） | ~180 |
| 19 | `SftpBatchProgressDialog.kt` | 批量任务进度弹窗 + 取消（§17.3.6） | ~100 |
| 20 | `SftpConnectEditPage.kt` | 新建/编辑连接表单 + 测试连接按钮 + 指纹确认弹窗（§21.8.4） | ~220 |

### 0.4 文档预览页面（`demo/.../pages/sftp/viewer/`）

| # | 文件 | DSL | 行数估 |
|---|------|-----|-------|
| 21 | `SftpViewerPage.kt` | 自研 — 按 `mimeHint` 分发到对应 viewer（§19.3） | ~80 |
| 22 | `viewer/SftpTextViewerPage.kt` | 自研 — RichTextView + 行号 + 二进制嗅探 + 超长行截断（§19.4） | ~150 |
| 23 | `viewer/SftpMarkdownViewerPage.kt` | **Compose** — `com.tencent.kuiklybase:markdown` 渲染（§19.5） | ~100 |
| 24 | `viewer/SftpImageViewerPage.kt` | 自研 — ImageView + 双指缩放 + 旋转（§19.6） | ~140 |
| 25 | `viewer/SftpPdfViewerPage.kt` | 自研 — `download` 到 cacheDir + 平台 PDF 渲染器适配（§19.7） | ~120 |
| 26 | `viewer/SftpHtmlViewerPage.kt` | 自研 — WebView + 禁 JS + 禁外部资源（§19.8） | ~100 |
| 27 | `viewer/SftpAudioPlayerPage.kt` | 自研 — 纯音频布局 + 锁屏控制（§21.3.10） | ~120 |

### 0.5 选集组件 + 主题 + 无障碍

| # | 文件 | 内容 |
|---|------|------|
| 28 | `widget/SftpEpisodeDrawerView.kt` | 选集底部抽屉组件（§20.6） |
| 29 | `widget/SftpContinuePlayDialog.kt` | 续播提示弹窗（§20.3） |
| 30 | `widget/SftpNextEpisodeCountdownDialog.kt` | 自动下一集倒计时弹窗（§20.7） |
| 31 | `theme/SftpColorTokens.kt` | 7 个 color token 日夜双色（§21.8.8） |
| 32 | `theme/SftpAccessibility.kt` | accessibilityLabel/Value/Hint 工具（§21.8.10） |

### 0.6 Compose DSL 共用基础（为 Markdown viewer 准备）

| # | 文件 | 内容 |
|---|------|------|
| 33 | `demo/build.gradle.kts` | 加 `com.tencent.kuiklybase:markdown` 依赖（已在 chatDemo 验证可用） |

---

## Phase 1 — Android 原生层

> 全部在 `core-render-android/src/main/kotlin/com/tencent/kuikly/render/android/module/sftp/`，需 Android SDK 但**不需要真机**就能编译。

### 1.1 SFTP Module 原生实现

| # | 文件 | 内容 | 行数估 |
|---|------|------|-------|
| 34 | `KRSftpModule.kt` | `KuiklyRenderBaseModule` 子类，`call(method, params, callback)` 分派全部 17 个方法；内部用 JSch 实现 SFTP 协议 | ~600 |
| 35 | `KRSftpClient.kt` | JSch `Session`/`ChannelSftp` 封装 + session pool + keepalive + 自动重连 | ~250 |
| 36 | `KRSftpFileHandle.kt` | `openRead/read/close` 流句柄管理 + `ConcurrentHashMap` | ~80 |

### 1.2 收藏 + 历史原生实现

| # | 文件 | 内容 | 行数估 |
|---|------|------|-------|
| 37 | `KRSftpFavoritesModule.kt` | `EncryptedSharedPreferences` 持久化 + `add/remove/removeByConnection/list/isFavorited/update/search` | ~200 |
| 38 | `KRSftpPlaybackHistoryModule.kt` | 同上存储层 + 7 方法 + LRU 容量管理（§21.5.2） | ~220 |

### 1.3 本地 HTTP 代理

| # | 文件 | 内容 | 行数估 |
|---|------|------|-------|
| 39 | `LocalHttpProxyServer.kt` | NanoHTTPD 子类，端口 18080-18089 fallback + token 管理 + Range→SFTP lseek+read + `X-Duration` 头 + token TTL 2h | ~350 |
| 40 | `LocalMediaProxyApi.kt` | `actual class` 实现 `expect`（§6） | ~60 |

### 1.4 依赖与构建

| # | 文件 | 改动 |
|---|------|------|
| 41 | `core-render-android/build.gradle.kts` | 加 `jsch:0.2.21` + `nanohttpd:2.3.1` |

---

## Phase 2 — iOS/macOS 原生层

> 全部在 `core-render-ios/Extension/Modules/Sftp/`，ObjC，需 Xcode + CocoaPods 编译。

### 2.1 SFTP Module 原生实现

| # | 文件 | 内容 | 行数估 |
|---|------|------|-------|
| 42 | `KRSftpModule.h` / `.m` | NMSSH 封装 + 17 方法分派 + session pool + keepalive | ~700 |
| 43 | `KRSftpSession.h` / `.m` | `NMSSHSession` + `NMSSHChannel` 管理 + 自动重连 | ~250 |
| 44 | `KRSftpFileHandle.h` / `.m` | 流句柄 + `NSMutableDictionary` | ~80 |

### 2.2 收藏 + 历史

| # | 文件 | 内容 | 行数估 |
|---|------|------|-------|
| 45 | `KRSftpFavoritesModule.h` / `.m` | Keychain 持久化 + 7 方法 + LRU | ~250 |
| 46 | `KRSftpPlaybackHistoryModule.h` / `.m` | 同上 + 7 方法 | ~270 |

### 2.3 本地 HTTP 代理

| # | 文件 | 内容 | 行数估 |
|---|------|------|-------|
| 47 | `KRLocalHttpProxy.h` / `.m` | GCDWebServer 子类 + 端口 fallback + token + Range→NMSSH read | ~400 |

### 2.4 依赖

| # | 文件 | 改动 |
|---|------|------|
| 48 | `demo.podspec` / `iosApp/Podfile` / `macApp/Podfile` | 加 `pod 'NMSSH'` + `pod 'GCDWebServer'` |

---

## Phase 3 — HarmonyOS 原生层

> 全部在 `core-render-ohos/cpp/sftp/`，C++ + NAPI，需 OHOS NDK 交叉编译。

### 3.1 libssh2 交叉编译（前置）

| # | 文件 | 内容 |
|---|------|------|
| 49 | `core-render-ohos/cpp/thirdparty/libssh2/CMakeLists.txt` | 鸿蒙 NDK 交叉编译脚本 |
| 50 | `core-render-ohos/cpp/thirdparty/libssh2/prebuilt/` | 预编译 `.so` + `.h` |

### 3.2 SFTP Module 原生实现

| # | 文件 | 内容 | 行数估 |
|---|------|------|-------|
| 51 | `sftp_wrapper.h` / `.cpp` | libssh2 封装 + 17 方法 + session pool + keepalive | ~800 |
| 52 | `sftp_session.h` / `.cpp` | `LIBSSH2_SESSION` + `LIBSSH2_SFTP` 管理 | ~250 |
| 53 | `sftp_favorites_storage.h` / `.cpp` | `@ohos.data.preferences` + Huks 加密 | ~200 |
| 54 | `sftp_history_storage.h` / `.cpp` | 同上 | ~220 |
| 55 | `napi_sftp_init.cpp` | NAPI 注册 + 方法分派 | ~300 |

### 3.3 本地 HTTP 代理

| # | 文件 | 内容 | 行数估 |
|---|------|------|-------|
| 56 | `local_http_proxy.h` / `.cpp` | libmicrohttpd 或自写 + Range→libssh2 read | ~400 |

---

## Phase 4 — Web/MiniApp 适配（可选，SFTP 受沙箱限制）

> 浏览器无 TCP/SSH，仅实现 UI + 调用后端网关（Phase 5 of §10 实施路线）。

| # | 文件 | 内容 |
|---|------|------|
| 57 | `core-render-web/src/jsMain/.../SftpModule.kt` | `actual` 走 WebSocket 网关 |
| 58 | `core-render-web/src/jsMain/.../SftpFavoritesModule.kt` | `actual` 走 `localStorage` |
| 59 | `core-render-web/src/jsMain/.../LocalMediaProxyApi.kt` | `actual` 直接返回网关 URL |

---

## Phase 5 — 宿主 App 注册 + 入口按钮

> 让 SFTP 入口在四端 App 里可见。

| # | 文件 | 改动 |
|---|------|------|
| 60 | `core-render-android/.../KuiklyRenderViewBaseDelegator.kt` | `registerModule` 加 3 个 `moduleExport` |
| 61 | `core-render-ios/.../KuiklyRenderViewControllerBaseDelegator.m` | 注册 3 个 Module |
| 62 | `core-render-ohos/.../napi_init.cpp` | 注册 3 个 Module |
| 63 | `demo/src/commonMain/.../main/MainPage.kt` | demo 首页加「SFTP 客户端」入口按钮 |
| 64 | `demo/src/commonMain/.../pages/MainActivityPage.kt` | 注册 SFTP 路由 |

---

## Phase 6 — 单元测试（可选，验证逻辑层）

| # | 文件 | 内容 |
|---|------|------|
| 65 | `core/src/commonTest/kotlin/.../SftpErrorCodeTest.kt` | 错误码解析测试 |
| 66 | `core/src/commonTest/kotlin/.../SftpMediaUrlBuilderTest.kt` | URL 构建 + Range 解析测试 |
| 67 | `demo/src/commonTest/kotlin/.../SftpPlaybackRecordTest.kt` | 续播记录序列化测试 |

---

## 执行顺序与依赖

```
Phase 0 (commonMain)
  ├─ 0.1 数据模型 (1-10)        ← 先做，无依赖
  ├─ 0.2 ModuleConst (11)       ← 与 0.1 并行
  ├─ 0.3 UI 页面 (12-20)        ← 依赖 0.1
  ├─ 0.4 预览页面 (21-27)       ← 依赖 0.1 + 0.3 的 base
  ├─ 0.5 组件/主题 (28-32)      ← 依赖 0.3
  └─ 0.6 Compose 依赖 (33)      ← 0.4 的 Markdown viewer 依赖

Phase 1 (Android) ← 依赖 Phase 0 全部
Phase 2 (iOS/macOS) ← 与 Phase 1 并行
Phase 3 (HarmonyOS) ← 与 Phase 1/2 并行
Phase 4 (Web) ← 可选，延后
Phase 5 (注册+入口) ← 依赖 Phase 0~3
Phase 6 (测试) ← 依赖 Phase 0
```

## 本次会话执行范围

由于上下文与单次输出长度限制，本次会话按以下顺序执行：

1. **Phase 0.1 + 0.2**：数据模型 + ModuleConst（10+1 文件）— 这是整个方案的基石
2. **Phase 0.3**：SFTP UI 7 页面（12-20）
3. **Phase 0.4**：6 个预览 viewer（21-27）
4. **Phase 0.5**：组件 + 主题（28-32）
5. **Phase 1.1-1.3**：Android 原生 SFTP（34-40）
6. **Phase 2.1-2.3**：iOS/macOS 原生（42-47）
7. **Phase 3.1-3.3**：HarmonyOS 原生（49-56）
8. **Phase 5**：注册 + 入口（60-64）

每 Phase 完成后标记 todo 为 completed，构建验证（§22.2 V0）留到全部代码就绪后统一做。

## 代码风格约束（来自 AGENTS.md）

- commonMain 包名：`com.tencent.kuikly.core.module.sftp.*`（core 模块）/ `com.tencent.kuikly.demo.pages.sftp.*`（demo 模块）
- compose 模块：仅 `androidx.compose.runtime.*` 用官方包，其余 `com.tencent.kuikly.compose.*`
- 禁 `core/` 或 `compose/` 依赖 `core-render-*`
- 自研 DSL：继承 `Pager()` + `body(): ViewBuilder`
- Compose DSL：`ComposeContainer` + `setContent {}` + `@Composable`，同一 Page 不可混用
- Module 通信：`asyncToNativeMethod`（JSON）/ `syncToNativeMethod`（原子 ByteArray）
- Angular commit：`feat:` / `fix:` / `refactor:`
