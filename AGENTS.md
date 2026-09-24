# AGENTS.md

> 本文件是 KuiklyUI 仓库的 AI 导航索引。任何 AI 编码代理（CodeFlicker / Codex / Cursor / Claude Code 等）在本仓库工作前 **必须先读完本文件**，再按需阅读「关键入口」指向的具体文档。

---

## 1. 项目概述

**KuiklyUI** 是 Tencent 开源的 Kotlin Multiplatform（KMP）跨端 UI 框架，一份代码六端运行：

- ✅ Android（aar）
- ✅ iOS（framework）
- ✅ HarmonyOS（.so）
- ✅ Web（js，Beta）
- ✅ 小程序（js，Beta）
- ✅ macOS（framework，Alpha）

两种 DSL：**自研 DSL**（`Pager + body()`）与 **Compose DSL**（`ComposeContainer + setContent{}`），二者不可混用。

详细文档：<https://framework.tds.qq.com/>

---

## 2. 模块地图（Module Map）

| 模块 | 路径 | 职责 | 性质 |
|------|------|------|------|
| core | `core/` | KMP 核心：Pager、DeclarativeBaseView、NativeBridge、ReactiveObserver、Module、Views | 纯 KMP，禁依赖 renderer |
| compose | `compose/` | Compose DSL：ComposeContainer、KuiklyApplier、布局/手势/动画 | 纯 KMP，禁依赖 renderer |
| core-annotations | `core-annotations/` | 注解定义：`@Page`、`@PageModule` 等 | 纯 KMP |
| core-ksp | `core-ksp/` | KSP 处理器：自动生成 Page/Module 注册代码 | 编译期 |
| core-render-android | `core-render-android/` | Android 原生渲染器（Java/Kotlin） | Android |
| core-render-ios | `core-render-ios/` | iOS 原生渲染器（ObjC，前缀 `KR`/`TDF`） | iOS/macOS |
| core-render-ohos | `core-render-ohos/` | HarmonyOS 渲染器（C++/ETS/NAPI） | HarmonyOS |
| core-render-web | `core-render-web/` | Web 渲染器（Kotlin/JS） | Web/MiniApp |
| core-wx | `core-wx/` | 微信小程序能力（可选依赖，不引入零成本） | KMP |
| demo | `demo/` | 示例代码（自研 DSL + Compose DSL） | KMP |
| androidApp | `androidApp/` | Android 宿主 shell | Android |
| iosApp | `iosApp/` | iOS 宿主 shell（Xcode） | iOS |
| macApp | `macApp/` | macOS 宿主 shell（Xcode） | macOS |
| ohosApp | `ohosApp/` | HarmonyOS 宿主 shell（DevEco） | HarmonyOS |
| h5App / h5App-js | `h5App*/` | Web 宿主 | Web |
| miniApp / miniApp-js | `miniApp*/` | 小程序宿主 | MiniApp |
| buildSrc | `buildSrc/` | 构建：编译/打包/产物拆分脚本 | Gradle |
| publish | `publish/` | 发布配置 | Gradle |
| openspec | `openspec/` | spec-driven 变更管理（每个 change 一个目录） | 文档 |
| docs | `docs/` | 对外文档站源文件 | 文档 |
| devDocs | `devDocs/` | 开发内部文档 | 文档 |

---

## 3. 关键约束（务必遵守）

1. **包名**：Compose 仅 `androidx.compose.runtime.*` 用官方包，其余一律 `com.tencent.kuikly.compose.*`。
2. **依赖方向**：`core/` 与 `compose/` 是纯 KMP 模块，**禁止**依赖任何 `core-render-*`。
3. **DSL 不可混用**：自研 DSL 与 Compose DSL 在同一 Page 内不可混用。
4. **Commit 格式**：Angular Convention — `feat:` / `fix:` / `docs:` / `refactor:` / `chore:`。
5. **平台 API 差异**：跨端行为不一致时，必须在 `openspec` spec 中显式记录每端行为。
6. **二进制通信**：`Module` 与原生通信支持 `String/Int/Float/ByteArray`；二进制走原子通道避免 base64 开销（见 `NetworkModule.httpRequestBinary`）。

详见 `openspec/config.yaml`。

---

## 4. 关键入口文件（先读这些）

| 主题 | 文件 |
|------|------|
| 理解 Module 桥模式 | `core/src/commonMain/kotlin/com/tencent/kuikly/core/module/Module.kt` |
| 已有 Module 样板 | `core/.../module/NetworkModule.kt`、`FileModule.kt`、`ModuleConst.kt` |
| Android 原生 Module 样板 | `core-render-android/.../module/KRNetworkModule.kt`、`KRFileModule.kt` |
| Android Module 注册 | `core-render-android/.../expand/KuiklyRenderViewBaseDelegator.kt`（`registerModule` ~L480） |
| iOS 原生 Module 样板 | `core-render-ios/Extension/Modules/KRNetworkModule.{h,m}`、`KRFileModule.m` |
| 视频组件（跨端 API） | `core/src/commonMain/kotlin/com/tencent/kuikly/core/views/VideoView.kt` |
| 视频组件 Android 适配 | `core-render-android/.../component/KRVideoView.kt`、`adapter/IKRVideoViewAdapter.kt` |
| 视频组件 iOS 适配 | `core-render-ios/Extension/AdvancedComps/KRVideoView.{h,m}` |
| Page 基类 | `demo/src/commonMain/kotlin/com/tencent/kuikly/demo/pages/base/BasePager.kt`、`core/.../pager/Pager.kt` |
| Demo Module 桥样板 | `demo/.../pages/base/BridgeModule.kt` |
| 构建配置（demo） | `demo/build.gradle.kts`（已用 `io.ktor:ktor-client-core` 验证 KMP 网络库可用） |

---

## 5. 构建与运行

### 5.1 环境要求
- Android Studio（Gradle JDK 17）
- Xcode + CocoaPods
- DevEco Studio 5.1.0+（API ≥ 18）
- JDK 17
- Kotlin 2.0.21（默认；其他版本见根目录 `build.<kotlin-version>.gradle.kts`）

### 5.2 运行各端 App

| 端 | 命令 / 步骤 |
|----|------------|
| Android | Android Studio 打开根目录 → sync → 选 `androidApp` → Run |
| iOS | `cd iosApp && pod install --repo-update` → Android Studio sync → 选 `iOSApp` → Run；或 Xcode 打开 `iosApp/` Run（`User Script Sandboxing` 设为 `No`） |
| macOS | `cd macApp && pod install --repo-update` → 选 `macOSApp` → Run |
| HarmonyOS | `./2.0_ohos_demo_build.sh` → DevEco 打开 `ohosApp` → 签名 → Run `entry` |
| Web | 见 `docs/QuickStart/h5.md` |

### 5.3 Kotlin 版本切换
根目录有 `settings.<version>.gradle.kts` + `build.<version>.gradle.kts`，对应不同 Kotlin 版本。默认 2.0.21。

---

## 6. 如何新增一个 Module（跨端能力）

1. **commonMain** 定义：
   - 在 `core/src/commonMain/kotlin/com/tencent/kuikly/core/module/<name>/` 新增 `class XxxModule : Module()`，`moduleName()` 返回常量。
   - 在 `ModuleConst.kt` 加 `const val XXX = "KRXxxModule"`。
   - 用 `asyncToNativeMethod` / `syncToNativeMethod` 调原生方法（参考 `NetworkModule`、`FileModule`）。
2. **Android**：在 `core-render-android/.../module/KRXxxModule.kt` 实现，继承 `KuiklyRenderBaseModule`，`call(method, params, callback)` 分派。在 `KuiklyRenderViewBaseDelegator.registerModule` 加 `moduleExport(KRXxxModule.MODULE_NAME) { KRXxxModule() }`。
3. **iOS/macOS**：在 `core-render-ios/Extension/Modules/KRXxxModule.{h,m}` 实现，`@implementation` 分派方法。在 `KuiklyRenderViewControllerBaseDelegator.m` 注册。
4. **HarmonyOS**：在 `core-render-ohos/cpp/` 写 NAPI 封装，注册到 C++ 模块表。
5. **Web/MiniApp**（可选）：`core-render-web` 或 `jsMain` 实现，受浏览器沙箱限制可能不支持。

样板：`NetworkModule` / `FileModule` 是最小可参考样本。

---

## 7. 如何新增一个 View（跨端组件）

1. **commonMain**：在 `core/src/commonMain/kotlin/com/tencent/kuikly/core/views/XxxView.kt` 定义 `class XxxView : DeclarativeBaseView<XxxAttr, XxxEvent>()`，实现 `createAttr/createEvent/viewName`，并 `fun ViewContainer<*, *>.Xxx(init: XxxView.() -> Unit)`。`ViewConst` 加 `TYPE_XXX_VIEW` 常量。
2. **Android**：`core-render-android/.../component/KRXxxView.kt` 继承 `KRView`，在 `KuiklyRenderViewBaseDelegator.registerRenderView` 加 `renderViewExport(KRXxxView.VIEW_NAME) { KRXxxView(it) }`。
3. **iOS**：`core-render-ios/Extension/Components/KRXxxView.{h,m}`，`@interface KRXxxView : UIView<KuiklyRenderViewExportProtocol>`。
4. **HarmonyOS**：`core-render-ohos` ETS/C++ 组件。
5. **Web**：`core-render-web` JS 实现。

样板：`VideoView` + `KRVideoView` + `IKRVideoViewAdapter`。

---

## 8. DSL 快速识别

- 看到继承 `Pager()` 并实现 `body(): ViewBuilder` → **自研 DSL**
- 看到 `ComposeContainer` + `setContent {}` + `@Composable` → **Compose DSL**
- 同一个 Page 不可混用两种 DSL。
- Demo 里两种都有：`demo/src/commonMain/kotlin/com/tencent/kuikly/demo/pages/` 下自研 DSL，`pages/compose/` 下 Compose DSL。

---

## 9. 现有能力清单（避免重复造轮子）

| 能力 | Module / View | 说明 |
|------|---------------|------|
| HTTP 请求 | `NetworkModule` | JSON / ByteArray 回包，支持 headers/cookie/timeout |
| 文件读写（沙盒） | `FileModule` | 写 / 追加 / 获取 cacheDir |
| 内存缓存 | `MemoryCacheModule` | — |
| 持久化 KV | `SharedPreferencesModule` | Android `EncryptedSharedPreferences` 可在宿主扩展 |
| 路由 | `RouterModule` | — |
| 通知 | `NotifyModule` | — |
| 编解码 | `CodecModule` | base64 等 |
| 日历 | `CalendarModule` | — |
| 字体 | `FontModule` | — |
| 性能 | `PerformanceModule` | — |
| Vsync | `VsyncModule` | — |
| 返回键 | `BackPressModule` | — |
| 反射 | `ReflectionModule` | 调 Java/OC 类 |
| 视频 | `VideoView` | `src/playControl/rate/muted/resizeMode` + 播放状态/时间/首帧事件 |
| 图片 | `ImageView` | — |
| 列表 | `ListView` / `PageListView` / `WaterfallListView` | 虚拟列表 |
| 文本 | `TextView` / `RichTextView` / `TextAreaView` / `InputView` | — |
| 滚动 | `ScrollerView` | — |

---

## 10. AI 在本仓库工作的流程

1. **先读本文件**（你已经在读了）。
2. **明确任务涉及的模块**：用第 2 节模块地图定位目录。
3. **读对应样板**：第 4 节关键入口文件，按场景读 1~2 个样板。
4. **检查约束**：第 3 节关键约束 + `openspec/config.yaml`。
5. **大改动走 openspec**：新建 `openspec/changes/<date>-<topic>/` 并按 `openspec/config.yaml` rules 写 proposal/spec/design/tasks。
6. **跨端能力（Module/View）**：按第 6/7 节流程，四端都要考虑；无法支持的端必须在 spec 显式记录。
7. **提交**：Angular commit 格式；PR 参照 `CONTRIBUTING.md`。

---

## 11. 已有的专题方案文档

| 文档 | 路径 | 主题 |
|------|------|------|
| 跨平台 SFTP 客户端 + 流式视频播放 | `docs/SFTP-Client.md` | SftpModule + SftpFavoritesModule + 本地 HTTP 代理 + VideoView 复用；完整文件管理（CRUD/权限/批量）+ 文件与文件夹收藏 + 点击即流式播放；四端落地方案。**先看本文件第 13 节的压缩上下文，再按需深入** |
| SFTP 实现详解（学习/实现向） | `docs/SFTP-实现详解.md` | 从架构到各端实现的完整走读：分层、Module 桥接、共享层、本地代理、三端原生实现、UI、测试、踩坑。**想理解「代码怎么写的、为什么这么写」优先看这篇** |
| **跨端应用开发规划（含 Electron 桌面壳）** | `devDocs/kuikly-app-development-plan.md` | 完整交付规划：目标/不变量/差距/里程碑 M0-M8/Electron 边界契约与专项设计/测试与安全/发布/风险/排期/DoD。**做新功能或桌面打包前先看这篇** |
| **自动化测试用例与执行规程** | `devDocs/sftp-test-plan.md` | 分层 L0-L5 用例（ID 稳定）、固定执行步骤、通过标准、失败排查、结果模板。**每次改动后照此跑**（一键：`bash scripts/run-all-tests.sh`）|
| **双栏文件管理器：测试用例与执行规程** | `devDocs/kuikly-dual-pane-test-plan.md` | 双栏（本地↔远端）验收用例 D0–D19、真实点击技法、踩坑清单；运行 `cd electron && npm run test:dual` |
| **文件双向传输模块：抽离与开发计划** | `devDocs/kuikly-file-transfer-module.md` | `core/file-manager` 纯状态机 + 双栏落地现状（§6）+ 未落地项（§6.5） |
| **SFTP 开发进度总览** | `devDocs/sftp-development-progress.md` | **当前进度快照**：六端现状矩阵、已交付能力清单、本轮 commit 详解、运行时验证步骤、已知限制与下一步。**想知道「现在做到哪了」优先看这篇** |
| SFTP 全平台开发计划 | `devDocs/sftp-impl-plan.md` | Phase 0~6 落地清单（commonMain 共享层 → 各端原生 → 注册 + 入口 → 单测），按 Phase 顺序开发，每 Phase 完成即可独立验证 |
| SFTP 播放页 mpv OSC 改造方案 | `devDocs/sftp-player-modernz-plan.md` | 精读 mpv `osc.lua` 362KB 源码后的改造方案：两行布局、10+ OSC 色彩、3 键 + 滚轮交互、可见性系统、60fps 节流、跨端降级策略 |
| AI 自动分析集成 | `openspec/specs/ai-integration/spec.md` | Profiler 报告取出通道、AI 分析流程 |
| Recomposition Profiler API | `openspec/specs/recomposition-profiler-api/spec.md` | 重组分析 API |
| 环境配置 | `docs/QuickStart/env-setup.md` | 开发环境搭建 |
| Hello World | `docs/QuickStart/hello-world.md` | 快速上手 |
| 组件概览 | `docs/QuickStart/overview.md` | 框架总览 |
| 各端集成 | `docs/QuickStart/{android,iOS,harmony,h5,Mac,Miniapp}.md` | 各端集成指南 |

---

## 12. 常见陷阱

- ❌ `core` / `compose` 模块里 `import com.tencent.kuikly.core.render.android.*` → 违反依赖方向。
- ❌ Compose 用 `androidx.compose.foundation.*` → 应为 `com.tencent.kuikly.compose.*`（runtime 除外）。
- ❌ 在 commonMain 直接 `import android.*` 或 `import UIKit` → 用 `expect/actual`。
- ❌ Module 方法里直接做 IO 阻塞 JS 线程 → 必须走 `asyncToNativeMethod`，原生侧在子线程执行。
- ❌ 新增 `Kotlin/Native` 平台时改 `kotlin {}` 后忘了同步更新 `settings.<version>.gradle.kts`。
- ❌ iOS `User Script Sandboxing = Yes` 导致 KMP 脚本权限错误 → 设为 `No`。
- ❌ 在 Web/MiniApp 假设有 TCP/SSH 能力 → 浏览器沙箱限制，必须走后端网关（Web 端已实现 `sftp-gateway/`，见 §13.1.3）。
- ❌ 在 VS Code / Kilo 环境直接 `electron .` → 会继承 `ELECTRON_RUN_AS_NODE=1`，Electron 退化纯 Node，
  `require('electron')` 只返回路径（`ipcMain` 为 undefined）。必须 `env -u ELECTRON_RUN_AS_NODE electron .`。

---

## 13. SFTP 客户端专题：现状、跨平台架构与实操要点

> 本节是 `docs/SFTP-Client.md`（3590+ 行设计文档）的**压缩上下文**，供 AI 快速进入状态。
> 深入细节看 `docs/SFTP-Client.md`，其中 **§23 跨平台架构** 与本节的 13.2~13.4 对应。

### 13.1 现状（真实可用性，不是"文件是否存在"）

| 端 | SSH/SFTP 协议栈 | 本地 HTTP 代理 | 状态 |
|----|----------------|----------------|------|
| **iOS / macOS** | NMSSH(libssh2 1.10.0，ridenui fork 2.7.2) `core-render-ios/Extension/Modules/KRSftp*.m` | GCDWebServer `KRLocalHttpProxy.m` | **可用**：两端各 74/74 集成自测（含字节级校验）；macOS 另做界面操控验证（流式播放/拖动 seek/暂停恢复） |
| Android | JSch 0.1.55 `core-render-android/.../expand/module/KRSftp*.kt` | NanoHTTPD `LocalHttpProxyServer.kt` | **可用**：74/74 集成自测（模拟器 Pixel_8a_API_35 / Android 15）+ 经代理用 ExoPlayer 播放 SFTP 视频出画 |
| HarmonyOS | libssh2（自 vendored mbedTLS）`core-render-ohos/src/main/cpp/.../sftp/` | 桩（未进 CMake）| **核心已实现**（连接/浏览/随机读/上传下载/文件管理/批量 + 收藏/历史/连接持久化）；本地代理与播放器未实现 → **暂不支持播放**；运行时未验证（本机无 OHOS 设备） |
| **Web (H5)** | 浏览器无原始 socket → **Node 网关代持**（`sftp-gateway/`，ssh2）| 网关直接出 HTTP Range（`/<token>/<file>`）| **可用**（2026-09 实测）：浏览器内完成连接/浏览/文件操作；`<video>` 经网关 Range 播放 SFTP 视频，控制面板经 CDP 模拟拖动验证 seek 生效 |
| 小程序 | 复用 Web 的 JS 模块 | 需网关 | **未验证**：同为浏览器沙箱，需网关网络可达 + 微信 request 域名白名单 |

Android 构建 / 验证（模拟器 Pixel_8a_API_35 / Android 15，JDK 17）：

```bash
export JAVA_HOME=<corretto-17>; export ANDROID_HOME=$HOME/Library/Android/sdk
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties   # 被 .gitignore 忽略
# 注意：仓库有多份按 Kotlin 版本命名的构建脚本，SFTP 依赖需存在于**当前生效**的那份
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
# 直达内页（宿主支持 pageName/pageData 两个 Intent extra）
adb shell am start -n com.tencent.kuikly.android.demo/.KuiklyRenderActivity \
  --es pageName SftpIntegrationTestPage --es pageData '{"host":"...","port":22,"user":"...","password":"..."}'
adb logcat -d | grep -oE '\[KLog\]\[SftpTest\]:[^"]*'   # 取 74 项断言结果
```

Android 侧的坑（都已修）：
- **按 Kotlin 版本命名的构建脚本容易漏改**：JSch / NanoHTTPD 当初只加在 `build.2.0.21.gradle.kts`，
  而生效的是 `settings.gradle.kts` 选中的 `build.2.1.21.gradle.kts` → 直接 Unresolved。
  改依赖时**必须补齐所有 `core-render-android/build.*.gradle.kts`**。
- `compose` 模块写死 `jvmTarget = "1.8"`，而 `:core` 用 JDK 17 默认目标 → 内联报
  "Cannot inline bytecode built with JVM target 17"（只在 compose 需重编译时暴露）。已对齐为 17。
- **Kuikly 模块回调不能直接塞裸 `JSONArray` / `JSONObject`**：Kotlin 侧会读不到
  （症状：列表恒为空、`get` 字段全空）。必须 `JSONObject(...).toMap()`（`toMap()` 会把嵌套数组转 List）。
- `KuiklyRenderLog` 只有 `i/d/e`（没有 `w`）。
- **随机读必须用 JSch 的 `get(src, monitor, skip)`**：`get(src)` 返回的是顺序流，
  对它 `skip(offset)` 会把绝对偏移当相对位移，多次读后位置错乱（播放器解析 MP4 直接失败）。
- 沉浸式（edge-to-edge）下状态栏会**吞掉点击**：页面自绘导航栏若不加顶部安全区，
  「+ 新建」点不动。SFTP 页面已统一加 `paddingTop(pagerData.statusBarHeight)` 的根容器。
- Android 视频适配器原本没实现 `playTimeDidChangedWithCurrentTime` → 播放页时间恒 `00:00/00:00`，已补轮询。

### 13.1.1 六端构建 / 验证矩阵（2026-09 实测）

| 端 | 构建 | 运行/功能验证 | 说明 |
|----|------|--------------|------|
| **macOS** | ✅ `xcodebuild` BUILD SUCCEEDED | ✅ 界面操控验证（播放/拖动 seek/暂停恢复） | SFTP 全链路可用 |
| **iOS** | ✅ `xcodebuild` BUILD SUCCEEDED | ✅ 模拟器 74/74 + AVPlayer 播放 | SFTP 全链路可用 |
| **Android** | ✅ `./gradlew :androidApp:assembleDebug` | ✅ 模拟器 **74/74 × 连续 10 轮**（零失败）+ ExoPlayer 经代理播放 SFTP 视频出画 | SFTP 全链路可用 |
| **HarmonyOS** | ✅ 渲染器 `libkuikly.so`（真实 CMake，含 SFTP）+ 业务 `libshared.so` 均构建通过 | ❌ 运行时未验证（本机无 OHOS 设备/模拟器镜像） | SFTP 核心已实现（连接/浏览/随机读/上传下载/文件管理等）+ 收藏/历史/连接持久化；视频本地代理与播放器未实现 |
| **Web (H5)** | ✅ `:demo:packLocalJsBundleDebug` + `:h5App:jsBrowserDevelopmentWebpack` | ✅ 浏览器实测（headless+CDP）：真实远端目录渲染 + 视频播放 + 拖动 seek（`00:06→00:04`）+ 设置菜单展开 | 需先起 Node 网关 `sftp-gateway`（127.0.0.1:18090）；详见 §13.1.3 |
| **MiniApp** | ✅ 同 Web（共用 JS bundle）+ `:miniApp:jsMiniAppDevelopmentWebpack` | ❌ 未验证 | 复用 Web 的 JS 模块；需网关可达 + 微信域名白名单 |

### 13.1.2 六端「全部编译一遍」实测（2026-09，本机 Intel Mac，不运行）

> 本轮把六端从"部分可构建"推进到**六端全部编译通过**，过程中修掉 9 个真实阻断项。

| 端 | 命令 | 结果 |
|----|------|------|
| macOS | `xcodebuild -workspace macApp.xcworkspace -scheme macApp -configuration Debug build` | ✅ BUILD SUCCEEDED |
| iOS | `xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build` | ✅ BUILD SUCCEEDED |
| Android | `./gradlew :androidApp:assembleDebug`（JDK 17） | ✅ BUILD SUCCESSFUL |
| HarmonyOS（渲染器） | `cmake` + DevEco `ohos.toolchain.cmake` 直接构建 `core-render-ohos/src/main/cpp/CMakeLists.txt` | ✅ `libkuikly.so`（aarch64，197 个 KRSftp 符号，255 个 libssh2 符号，0 未解析） |
| HarmonyOS（业务） | `./2.0_ohos_demo_build.sh` | ✅ `libshared.so` 并拷入 `ohosApp` |
| Web (H5) | `:demo:packLocalJsBundleDebug` + `:h5App:jsBrowserDevelopmentWebpack` | ✅ BUILD SUCCESSFUL（产物 `nativevue2.js`） |
| MiniApp | 同上 + `:miniApp:jsMiniAppDevelopmentWebpack` | ✅ BUILD SUCCESSFUL |

**本轮修复的阻断项（勿回退）**：

1. **`core-ksp` 缺 JS 入口构建器**（Web/MiniApp 必挂）：JS 目标落入 `else` 分支被当成 Android，生成的 `jsMain/KuiklyCoreEntry.kt` 引用 androidMain-only 的 `IKuiklyCoreEntry` → Kotlin/JS 编译必然失败。
   新增 `impl/JsTargetEntryBuilder`：**不生成任何平台代码**，只直写首行 `//页面名|页面名…`（Gradle 插件 `JSProcessor.getPageListFromEntryFile` 会取「最后一个 `/` 之后」按 `|` 切分页面列表，KotlinPoet 的 `// ` 前缀与折行会破坏该解析，故必须直写）。
   同时把页面筛选抽成 `selectPages()`，`getEntryBuilder()` 改为可返回 JS 构建器。
   ⚠️ 注意 `getEntryBuilder()` 依赖 `codeGenerator.generatedFile`，**必须在 `createNewFile` 之后调用**。
2. **iOS `EXCLUDED_ARCHS` 覆盖 Pod 设置**：`iosApp.xcodeproj` 把 `EXCLUDED_ARCHS[sdk=iphonesimulator*]` 设为空，
   导致模拟器同时构建 arm64，而 NMSSH 自带的 libssh2/libssl/libcrypto 只有 x86_64（Intel 机）→
   `found architecture 'x86_64', required architecture 'arm64'` + `linking object file built for 'iOS'`。
   改为 `$(inherited)`，让 podspec 的 `arm64` 排除生效。
3. **iOS Pods 未同步**：`pod install` 前 NMSSH/Pods-iosApp 目标不在构建计划里 → `ld: library 'NMSSH' not found`、`library 'Pods-iosApp' not found`。
   重跑 `pod install` 即可；**注意 CocoaPods 需要 UTF-8 locale**（否则 Ruby 报
   `Unicode Normalization not appropriate for ASCII-8BIT`）：`export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8`。
4. **OHOS `NODE_TEXT_INPUT/AREA_ON_WILL_CHANGE` 不存在于 API 19** → 整个渲染器编译失败。
   按仓库既有 `OH_CURRENT_API_VERSION` 模式加 `KUIKLY_TEXT_ON_WILL_CHANGE_AVAILABLE`（>=20），低版本不注册也不分发该事件（仅失去输入「改变前拦截」）。
5. **OHOS `OH_Drawing_TextVerticalAlignment` / `TEXT_VERTICAL_ALIGNMENT_CENTER` 不存在于 API 19** → 同上加
   `KUIKLY_DRAWING_VERTICAL_ALIGN_AVAILABLE`（>=20），低版本走既有手动校准分支。
6. **OHOS `NODE_IMAGE_SOURCE_SIZE` 不存在于 API 19** → 加 `KUIKLY_IMAGE_SOURCE_SIZE_AVAILABLE`（>=24），
   低版本 `SetArkUIImageSourceSize` 为 no-op，由 `SetArkUIImageCapInsetsWithLattice` 的运行时弱符号检测退回老四值路径。
7. **OHOS `librcp_c.so` 本机 SDK 无此库且源码无任何引用** → 改为按存在性条件链接（否则 `unable to find library -lrcp_c`）。
8. **OHOS SFTP thirdparty 路径用了相对 `NATIVERENDER_ROOT_PATH`（`.`）** → 在 DevEco 独立构建目录下
   `if(EXISTS …)` 失败、libssh2 不被链接（配置期只给 warning，最终链接期才炸）。改用 `CMAKE_CURRENT_SOURCE_DIR` 绝对路径。
9. 上述 OHOS 门控阈值（20 / 24）依据代码注释与仓库既有 `KUIKLY_TEXT_EDITOR_AVAILABLE(>=24)` 推定；
   若换用更高版本 SDK 且出现"符号本应存在却被跳过"，应据此调整阈值。

HarmonyOS 构建要点：
- `./2.0_ohos_demo_build.sh` 会自动把 wrapper 切到 **Gradle 8.0**、用 **`Kotlin 2.0.21-KBA-010`**（华为定制版）
  与 `settings.2.0.ohos.gradle.kts`，跑 `:demo:linkSharedDebugSharedOhosArm64`，最后把
  `libshared.so` / `libshared_api.h` 拷进 `ohosApp`。
- OHOS SDK 在 **DevEco 内部**：`/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/sysroot`
  —— `~/Library/Huawei/Sdk` 为空**不影响** Kotlin/Native 链接。
- 首次构建约 20+ 分钟（要下载 Kotlin/Native 工具链与 ohosArm64 依赖）；中途若出现
  `SSLHandshakeException / SSL peer shut down incorrectly`（下载 knoi-processor 等 jar 时），
  是代理网络抖动，**直接重跑即可**（编译缓存会复用）。
OHOS SFTP 现状（2026-09 实测）：
- **依赖已 vendor 进仓库**：`core-render-ohos/src/main/cpp/thirdparty/libssh2-ohos/arm64-v8a/`
  内含交叉编译好的 `libssh2.a` + mbedTLS 2.28.8（`libmbedtls/libmbedx509/libmbedcrypto`）静态库与头文件。
  重建方式：用 DevEco 自带 `ohos.toolchain.cmake`（`--target=aarch64-linux-ohos`）编译 mbedTLS
  （`-DCMAKE_C_FLAGS="-Wno-unused-command-line-argument -Wno-error"`，否则 clang 15 会把
  `--gcc-toolchain` 判为 unused 而因 `-Werror` 失败），再以 `-DCRYPTO_BACKEND=mbedTLS` 编译 libssh2。
- **CMake 已接线**：`core-render-ohos/src/main/cpp/CMakeLists.txt` 的 `SOURCE_SET` 已加入 sftp 全部源文件
  （此前**根本没进构建**，且 `KRSftpModule.cpp` 调用了并不存在的 `KRJSONObject::FromAnyValue` → 无法编译），
  并在 `target_link_libraries` 中按 `${OHOS_ARCH}` 链接上述静态库。
- **实现**：`KRSftpSession`（libssh2 阻塞式会话，TCP 带超时，密码/公钥认证；list/stat/mkdir/rm/
  rename/move/copy/chmod/chown/setMtime/upload/download/batchTask）、`KRSftpFileHandle`（随机读，
  `[meta, ByteArray]` 原子通道）、三个存储模块（JSON 文件持久化，连接 label 去重 + 删连接级联清收藏/历史，
  历史 2000 条 LRU）。
- **值通道约定**：OHOS 侧 `KRAnyValue` 只有 `toLong()/toInt()/toMap()/toArray()/toString()`；
  取参数用 `params->toMap()`，Map/Array 的 `toString()` 直接得到 JSON。`Make(long long)` 在 LP64 下
  **重载歧义**，必须写 `static_cast<int64_t>(x)`。
- **实现期/复查期修掉的关键缺陷（勿回退）**：
  1. `Connect` 误读 `username`/`privateKeyPath`（Kotlin 实发 `user`/`privateKey`）→ 连接恒失败。
  2. `Download` 拿**本地**路径的父目录去**远端** `mkdir` → 下载必失败；改用 `EnsureLocalDir`。
  3. `Copy` 以 `/data/local/tmp` 作中转（OHOS 沙盒不可写）+ 先整份下载到 `/dev/null`（双倍传输）
     → 改为真正的远端→远端流式复制 `CopyRemoteToRemote`。
  4. `BatchTask` 契约理解错误（当作带 `type` 的对象数组）→ 重写为 `action` + 字符串 `items`，对齐 Android。
  5. `CancelBatchTask` 曾调 `Disconnect(taskId)`（会误断正常会话）→ 改为幂等空操作。
  6. 三个存储模块 `MODULE_NAME` 未定义（注册表引用）→ 补定义。
  7. `OnDestroy` 曾全局 `ShutdownAll/CloseAll`，会断掉其它 Page 的会话 → 对齐 Android/iOS 不清理。
  8. 上传续传用 `LIBSSH2_FXF_APPEND` + `seek64`（APPEND 忽略 seek）→ 去掉 APPEND。
  9. batch MOVE/COPY 未先建目标目录 → 补 `EnsureRemoteDir(targetDir)`。
  10. `Disconnect` 未置 `broken`，并发可能解引用已释放句柄 → 补标记。
- **未实现**：`KRLocalHttpProxy`（仍是 stub，无 HTTP server，且**未进 SOURCE_SET**）→ Kotlin `SftpMediaProxyModule`
  （`KRLocalMediaProxyModule`）在 OHOS 无原生实现；`core-render-ohos` 也没有视频组件 → **OHOS 暂不支持播放 SFTP 视频**。
- **验证方式（无设备时）**：
  1. `cmake` + DevEco `ohos.toolchain.cmake` 独立编译 sftp 源并链接 libssh2/mbedTLS → aarch64 `libsftpcheck.so`
     （无未解析应用符号，仅剩 libc++/系统库符号）。
  2. **注册探针**（`registration_probe.cpp`）：按 `ModulesRegisterEntry.h` 的方式引用 4 个 `MODULE_NAME`
     并构造各模块实例，把「注册表引用但未定义」这类**只在完整构建才暴露的断链**提前暴露
     —— 该探针正是发现了存储模块 `MODULE_NAME` 未定义。
  3. **字段名机械核对**：比对 Kotlin `params.put/json.put` 发出的 key 与 C++ 读取的 key
     —— 该核对发现了 `Connect` 误读 `username`/`privateKeyPath`（Kotlin 实发 `user`/`privateKey`）。
  真机/模拟器就绪后需补：`./2.0_ohos_demo_build.sh` → DevEco 构建 `libkuikly.so` → 运行 SFTP 测试页。

OHOS 侧跨端行为差异（与 Android 实测对照，需知悉）：
- `list` 的 `includeHidden` / `offset` / `limit` / `hasMore`：**OHOS 已实现**（按 limit 截断并置 hasMore），
  Android 未实现（忽略这三个参数、恒 `hasMore=false`）。默认参数（`includeHidden=true, limit=10000`）下两者结果一致。
- `overwrite`（`SKIP` / `FAIL` / `RENAME_APPEND_SUFFIX`）：**OHOS 与 Android 均未实现**，一律按覆盖处理。
- `batchTask`：两端一致（`action` 默认 `DELETE`、大小写不敏感、逐项容错后汇总抛错、成功返回 `1.0f`；
  batch MOVE/COPY 先建目标目录）。

构建环境（本机实测，Intel Mac）：JDK 17（corretto）+ Gradle 7.6.3（OHOS 用 8.0）+ Xcode 26.3 +
Android SDK（Pixel_8a_API_35 / Android 15 模拟器）+ DevEco Studio（内置 OHOS SDK）。
**所有需要外网依赖的下载都走本机 Clash 代理 `127.0.0.1:7897`**（`pod install` / Gradle 均需）。

各层位置：
- 业务/UI（100% 共享）：`demo/src/commonMain/.../pages/sftp/`（首页/浏览/编辑/播放/属性/预览器）
- 能力声明（100% 共享）：`core/src/commonMain/.../module/sftp/`（`SftpModule` / `SftpConnectionModule` / `SftpFavoritesModule` / `SftpPlaybackHistoryModule` / `SftpMediaProxyModule`）+ 数据模型 + `I18n` + `MimeExtMap` + `SftpMediaUrlBuilder`
- 原语桥接：Kuikly Module（**模块名 == 原生类名**，框架用 `NSClassFromString`/注册表解析；入参 JSON 字符串，出参 JSON）
- 平台实现：见上表

### 13.1.3 Web(H5)/JS 端实现：Node 网关 + 浏览器模块（2026-09 新增）

浏览器无法建立原始 TCP/SSH，Web 版 SFTP 由**后端进程代持** SSH/SFTP，浏览器只做桥：

```
浏览器（Kuikly Web）                        Node 网关 sftp-gateway/
 SftpModule 等 5 个 JS 模块  ──POST /rpc──▶   ssh2 会话（连接/浏览/随机读/文件操作/持久化）
 KRLocalMediaProxyModule    ──registerToken─▶
 <video>  ◀── HTTP Range  /<token>/<file> ──  直接以 Range 读远端文件
```

- 网关：`sftp-gateway/server.js`（Node + `ssh2`），默认监听 `127.0.0.1:18090`
  - `POST /rpc {module,method,params}`：`sftp`（19 个方法，语义对齐原生）+ `mediaProxy`
    + `connection`/`favorites`/`history`（JSON 文件持久化到 `sftp-gateway/data/`，**已 gitignore**）
  - `GET /<token>/<fileName>`：HTTP Range（206/416 + `Content-Length`），URL 与
    `SftpMediaUrlBuilder.buildPlayUrl` 完全一致（`http://127.0.0.1:<port>/<token>/<name>`）
  - **安全（主机指纹）**：`connect` 传 `hostKeyPolicy` = `TOFU`(默认)/`STRICT`/`INSECURE`，
    首次连接记录指纹到 `data/sftp_known_hosts.json`，不匹配抛 `HOSTKEY_MISMATCH` → 错误码 `1004`；
    并提供 `knownHosts.list` / `knownHosts.remove`。
    **Android 已接（JSch `HostKeyRepository`，TOFU，编译验证通过）；iOS/OHOS 端待补（M1-1 未完）**。
- 浏览器模块：`h5App/src/jsMain/kotlin/module/SftpGatewayModules.kt`（Kotlin/JS，5 个类转发到 `/rpc`），
  在 `h5App/src/jsMain/kotlin/KuiklyWebRenderViewDelegator.kt` 的 `registerExternalModule` 注册；
  **commonMain 页面零改动**。网关地址默认 `http://127.0.0.1:18090`，可用
  `window.__SFTP_GATEWAY_URL__` 覆盖
- 宿主 → 页面事件（Web 专有，`h5App/src/jsMain/kotlin/Main.kt`）：
  - `mousemove/touchstart` → `sftp_controls_activity`（全屏播放时移动鼠标唤醒控制条）
  - `keydown` → `sftp_player_key`（空格/K、←/→、M、F；输入框内不拦截）
  - `fullscreenchange` → `sftp_fullscreen_changed`（ESC 退出全屏时同步页面状态）
  - 页面侧用 `addPagerEventObserver(IPagerEventObserver)` 接收（`SftpPlayerPage`）
- `KRVideoView`（`core-render-web/base/.../expand/components/KRVideoView.kt`）新增：
  - `setFullscreen`：对**含控件的根容器**（`#root`）请求全屏 —— 只全屏 `<video>` 会让
    Kuikly 绘制的控件留在普通文档流，视频与控件不在同一层级、全屏看不到控件
  - `buffered`：`progress` 事件经 `customEvent` 通道回传已缓冲进度
- 播放控件（`demo/.../SftpPlayerPage.kt`，commonMain 六端共享，参考 Plyr）：覆盖式面板、
  缓冲/已播/滑块三段进度条、拖动时间气泡、倍速设置菜单、中央大播放键、2s 自动隐藏

运行（本机实测）：
```bash
# 1) Node 网关（本机 Node 可用 ~/.gradle/nodejs/node-v22.0.0-darwin-x64/bin 或 /usr/local/bin/node）
cd sftp-gateway && npm install
PATH="$HOME/.gradle/nodejs/node-v22.0.0-darwin-x64/bin:$PATH" node server.js
# 2) 业务 bundle + 宿主（必须 JDK 17：默认 JDK 25 与 Gradle 7.6.3 不兼容）
export JAVA_HOME=<corretto-17>
./gradlew :demo:packLocalJsBundleDebug -Pkuikly.useLocalKsp=false
./gradlew :h5App:jsBrowserDevelopmentWebpack
# 3) 静态托管：8083 出 nativevue2.js、8080 出 index.html+h5App.js，浏览器开
#    http://127.0.0.1:8080/?page_name=SftpHomePage
```

自动化测试（无需 Playwright，Node(≥22) + CDP 驱动 Chrome）：
```bash
cd sftp-gateway
npm run test:rpc   # 只测网关 RPC / 媒体 Range / 文件操作（不需要浏览器）
npm test           # 全量：再跑浏览器 UI（首页 / 浏览真实目录 / 视频播放 / 拖动 seek / 设置菜单）
npm run e2e        # 全自动：自动探测 JDK17/Node、按需构建、自启网关+页面、跑测试、清理
                   #   E2E_BUILD=1 强制重建；E2E_KEEP=1 保留服务；E2E_TEST_TIMEOUT=300
HEADLESS=0 SLOWMO=200 npm test   # 有头（可见浏览器，能直接看到自动化操作）并放慢
```
- 用例：`sftp-gateway/test/sftp-web.test.js`；编排：`sftp-gateway/scripts/e2e.sh`
- 可用 `GATEWAY_URL` / `WEB_URL` / `CHROME_PATH` / `SFTP_HOST|PORT|USER|PASSWORD|HOME|MEDIA` 覆盖默认值
- 前置：`npm test` 需网关与页面 8080/8083 已起；`npm run e2e` 会自己起（未监听才起，结束时关掉自己起的）
- 校验强度：随机读与媒体 Range 均做**字节级**比对（`ftyp` 头 + offset 50000 中段）
- 已知测试差异：**CDP 合成的 pan move 在 headless 下不稳定**，故「拖动 seek」只在 `HEADLESS=0` 断言；
  无头下 seek 能力由「键盘 seek（←/→）」用例覆盖。每次运行使用独立 Chrome profile，避免实例复用导致白屏

已知限制（Web）：
- 网关会话在**内存**中，重启网关即失效：旧 `sessionId` 的播放页 seek 会失败，需从首页重进
- 与原生端一致**未做 known_hosts 校验**（安全审计 P0）
- 缓冲条目前仅 Web 有（其它端引擎未回传 buffered）；无音量滑杆（`VideoView` 无 volume 属性）
- 小程序复用同一批 JS 模块，但需网关网络可达 + 微信 request 域名白名单，未验证

### 13.2 新增一个 SFTP 能力的五步（缺一不可）

1. `core/src/commonMain/.../module/sftp/XxxModule.kt` 用 `asyncToNativeMethod` 声明方法
2. `ModuleConst.kt` 加模块名常量
3. 各端实现**同名类**并分发 `hrv_callWithMethod`（iOS/macOS: `KRBaseModule` 子类）
4. 页面 `acquireModule(XxxModule.MODULE_NAME)`，并在 `createExternalModules()` 注册
5. **Web(H5)**：`h5App/src/jsMain/kotlin/module/SftpGatewayModules.kt` 加同名转发类并在
   `KuiklyWebRenderViewDelegator.registerExternalModule` 注册；网关侧在 `sftp-gateway/server.js`
   的对应 module 加方法（浏览器不能直连 SSH，必须由网关代持，见 §13.1.3）

> 本地媒体代理**统一走 Module**，不要再用 `expect/actual`。历史上两套并存（`LocalMediaProxyApi` 已删除），
> 详见 `docs/SFTP-Client.md` §23.2 的选型对比表。

### 13.3 跨端一致性三条硬规则

1. **桩实现必须显式失败，绝不伪报成功**。反例（已修）：OHOS `Connect` 不连接返回假 sessionId、
   `Upload/Download` 直接 `return 1.0f`、`Copy` 返回 success → 用户看到"上传 100%"而远端无文件（静默数据丢失）。
   未实现统一抛 `not implemented` → 错误码 `9999`。
2. **错误码跨端同一套语义**（`SftpErrorCode`）：`1001` 连接 / `1003` 认证 / `2001` 权限 /
   `2003` 文件不存在 / `3001` 协议 / `9999` 未实现。各端 `SftpErrorFormatter` 负责映射，UI 只认 code。
3. **侧效应放页面层，组件只渲染**。反例（已修）：`SftpImageViewer` 在 `body()` 里同步调代理拿 token，
   token 恒为空 → 图片预览一直是坏的。现由 `SftpViewerDispatcherPage` 异步申请后经 provider 传入。

### 13.4 Kuikly 框架踩坑（本仓库通用，改任何 Kuikly 页面都可能遇到）

按"踩过并已修"排序，每条都对应一次真实故障：

1. **响应式依赖只在 `attr {}` / `vif`/`vfor` 条件 lambda 内收集**。
   写在 `body()` 结构层的 `if/when`/局部变量在首帧求值一次，之后状态变化**不会重建分支**。
   症状：页面永远停在"加载中"、Tab 切换不刷新。修法：条件渲染一律用 `vif/velseif/velse`。
2. **状态字段必须是 `observable`**（列表用 `observableList` + `vfor`）。普通 `var` 改了不触发重渲染。
   症状：续播弹窗/倒计时/抽屉永不出现。另注意 `vfor` 配合 `observableList` 才能按 diff 增删。
3. **原生组件必须显式给尺寸**。布局引擎**不做原生控件测量**：
   - `Input` 不写 `height()` → 高度塌成 0 → 光标不可见、点击不聚焦、placeholder 不显示
   - `Image` 不写 `size()` → 0×0 不可见
   诊断手段：日志 `uilayout/viewTree` 会 dump 原生视图树（类名/frame/输入框状态），一眼看出 0 尺寸。
4. **回调里读写要留意线程**：异步回调回到 Kotlin 线程，但 libssh2/JSch 的 session **不是线程安全**的。
   iOS 端已把每个 Module 的调用串行到独立队列；其它端实现时同样要串行化。
   症状：刚 `mkdir` 立刻 `list` 看不到新目录、握手偶发失败。
5. **新增原生源文件后必须重跑 `pod install`**（iOS/macOS）。Pods 的文件列表是 `pod install` 生成的，
   新文件不重跑不参与编译 → 模块名解析失败 → DEBUG 下 `NSAssert` 直接 abort。
6. **`I18n.t(key)` 找不到 key 时回显 key 本身**（界面会出现 `sftp.viewer.xxx` 这种文案）。新增文案必须进 `I18n.kt`。
7. **DEBUG 下 `KRLogModule.logError` 会弹模态错误框**。自动化/批量运行时设 `KUIKLY_SUPPRESS_ERROR_ALERT=1` 关闭（已实现，且限制最多弹 3 次）。
8. **macOS 窗口内容区从标题栏下方开始**（约 28pt）。用屏幕坐标做 UI 自动化时，Kuikly 的 y=0 对应窗口 y≈28。
9. **macOS 文本输入**：`UITextField.tintColor`（光标色）与 `secureTextEntry`（密码掩码）在 `KRUIKit.m` 里都需要显式实现，
   系统 `NSTextField` 无这两个属性；掩码不能用 `NSSecureTextFieldCell` 替换（会导致 `controlTextDidChange` 不再回调）。
10. **拖拽手势是 `event { pan { } }`，不是 `touchDown/touchMove/touchUp`**。
    实测 macOS 上 `touch*` 对普通 View 不回调；`pan` 的 `PanGestureParams` 带 `state`(start/move/end) 与 `x/y`。
    进度条拖动用 `pan` 实现（`displayRatio` 拖动中显示预览、松手才真正 seek，避免拖一次发几十次 seek）。
11. **`VideoView` 的属性必须显式给尺寸**，且 `playControl` 必须跟随真实状态：
    - 只给外层容器尺寸时 `Video` 高度为 0，VLC 的渲染视图也是 0（`hasVideo=0`）→ 解码在跑、黑屏。
    - 不设 `playControl(PLAY)` 时 VLC 只创建播放器不会起播 → 本地代理收不到任何请求。
12. **`seekTo` 不要绑播放进度**：把 `currentPosition` 同时绑到 `attr { seekTo(...) }` 会导致每秒一次真实 seek
    （VLC 每次 seek 都 flush+重缓冲）→ 播放卡顿。用独立 `seekTarget`（-1 表示未请求），只在显式跳转时下发。
13. **`KRVideoView` 的 `_p_pendingSeekMs` 必须真正补发**：播放器处于 Buffering/ESAdded 时收到的 seek 会被暂存，
    若没有在进入可播状态后补发就会被永久丢弃（症状：进度条拖了位置变了但播放器没跳、暂停后恢复停在旧位置/直接 Ended）。
14. **本地代理的 Range 响应要带 `Content-Length` 的流式 206**：缺 `Content-Length` 时播放器会把该输入判为不可 seek
    （拖进度条后不请求新位置的数据）。同时不要为了「低内存」把单次 Range 硬截成很小分片，否则播放器反复重发同一 Range。
15. **页面的"是否在播放"应当表示"用户意图"，不要被播放器状态回调覆盖**。否则：
    - 暂停时拖进度条/快进/快退，seek 前为了让 VLC 的 demuxer 拉数据会临时起播，VLC 报
      `state=Playing` → 回调里把 `isPlaying = true` → 播放按钮立刻变 ⏸，与"用户想暂停"矛盾。
    修正：
    - 状态变化回调里**只把 `isPlaying` 置 false 的场景收窄到 PLAY_END / ERROR**，其它都别动它。
    - 原生侧用 `krv_userWantsPlay`（由 `krv_play` / `krv_pause` 维护）记录意图；`krv_seekToTime`
      在"需要恢复暂停"时临时起播让 demuxer 拉数据，seek 后 `dispatch_after` 几百毫秒再
      `pause`（若期间用户改了意图就尊重新意图）。

### 13.5 macOS / iOS 端实操：构建 / 运行 / 调试 / 自动化

```bash
# 首次或改了原生源文件后（需要代理才能拉 GitHub；端口按本机 Clash 配置）
cd macApp && pod install

# 构建
xcodebuild -workspace macApp.xcworkspace -scheme macApp -configuration Debug \
  -destination 'platform=macOS' -derivedDataPath build/DerivedData build

# 运行（自动化时用子进程方式启动才能注入环境变量）
export KUIKLY_SUPPRESS_ERROR_ALERT=1
macApp/build/DerivedData/Build/Products/Debug/macApp.app/Contents/MacOS/macApp
```

**iOS 端构建 / 运行**（同一台机、Xcode 26 验证过）：

```bash
cd iosApp && pod install          # 需要代理拉 GitHub（本机 Clash 端口 7897）
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'id=<模拟器 UDID>' -derivedDataPath build/DerivedData build

xcrun simctl boot <UDID>
xcrun simctl install <UDID> build/DerivedData/Build/Products/Debug-iphonesimulator/iosApp.app
xcrun simctl launch <UDID> com.tencent.kuiklycore.demo.luoyibu
xcrun simctl io <UDID> screenshot /tmp/ios.png
```

iOS 侧的坑（都已修）：
- `OpenKuiklyIOSRender` 在 iOS 的 Podfile 里开了 **`GCC_TREAT_WARNINGS_AS_ERRORS=YES`**（macApp 没开），
  任何 ObjC 告警都会变成编译失败。已知需保持为零告警：
  - `libssh2_sftp_open` 是**宏**，会把 `strlen()` 的 `size_t` 隐式窄化给 `libssh2_sftp_open_ex` 的
    `unsigned int`（`-Wshorten-64-to-32`）→ 直接调用 `libssh2_sftp_open_ex` 并显式 `(unsigned int)` 转换。
  - `+[NSKeyedArchiver archivedDataWithRootObject:]` / `+[NSKeyedUnarchiver unarchiveObjectWithData:]`
    在 iOS 12 起废弃（`-Wdeprecated-declarations`）→ 改用实例式
    `initRequiringSecureCoding:NO` + `decodeObjectOfClasses:forKey:`（保持经典归档格式，兼容旧数据）。
  - 不再使用的常量/变量会触发 `-Wunused-const-variable`。
- **NMSSH 版本**：iOS 的 Podfile 必须与 macOS 用同一个 ridenui fork（2.7.2，内 libssh2 1.10.0）。
  官方 `NMSSH ~> 2.3.1` 内 libssh2 1.8.0 与服务器 OpenSSH 8.9 握手失败（日志：TCP 通 →
  `Failure establishing SSH session` → `connect` 返回 1001，后续全部 `invalid sessionId`）。
- iOS 首页 `ContentView.swift` 原本 `.ignoresSafeArea()` 会让页面自绘导航栏压到状态栏/灵动岛下
  （与 macOS 同类问题）；指向 SFTP 页时去掉该修饰符。
- **iOS 侧 `KRBridgeModule` 缺 `toast:`**：SFTP 保存成功后页面会调 `toast`，iOS 没有该实现 →
  命中 `KRBaseModule` 里「module方法不存在」的 `NSAssert`，DEBUG 下**直接崩溃**（保存即崩）。
  已在 `iosApp/.../KuiklyRenderExpand/Modules/KRBridgeModule.m` 补 `toast:`（轻量非模态 HUD）。
  排查手法：崩溃栈里出现 `-[KRBaseModule hrv_callWithMethod:]` + `_userInfoForFileAndLine`
  就是这条路径；日志里同帧会打印 `[module] xxx.yyy (NO HANDLER)`。
- **打开视频崩溃（WMPlayer 5.0 与新系统不兼容）**：`+[WMPlayer IsiPhoneX]` 里访问
  `UIApplication.sharedApplication.delegate.window`，而 `UIApplicationDelegate.window` 是 optional 属性；
  **SwiftUI 生命周期**下系统 delegate 不实现 `window` → `doesNotRecognizeSelector:` → 崩溃
  （栈：`-[WMPlayer addUIControlConstraints]` ← `initPlayerModel:` ← `KRVideoViewHandler.load`）。
  修法：在 `iOSApp.swift` 用 `@UIApplicationDelegateAdaptor` 提供**带 `window` 属性**的 delegate
  （不改第三方库，可持久）。注意在子类里覆盖 `+IsiPhoneX` **无效** —— WMPlayer 内部是
  `[WMPlayer IsiPhoneX]` 类级调用，不走子类覆盖。
- **退出播放页仍在播放 / 多次进入叠播：NSTimer 保留环**。
  为补进度加了 `[NSTimer scheduledTimerWithTimeInterval:target:self ...]` 并把它存进
  强引用属性 → handler(播放器) 强引用 timer、timer 强引用 handler，**永不释放** →
  关闭页面后音频继续、再次进入又建一个播放器同时出声。
  修法：改用 block 版 `scheduledTimerWithTimeInterval:repeats:block:` 并捕获 `weakSelf`；
  同时在 `didMoveToWindow:`（window 为 nil）与 `removeFromSuperview` 里 `pause` + 停表。
  验证靠 WMPlayer 自带的 `NSLog(@"WMPlayer dealloc")` 与 CoreAudio 的
  `AudioQueue has stopped`：退出后应立即出现，且之后音频渲染日志为 0。
- **iOS 播放器实现已从 WMPlayer 换成系统 AVPlayer + AVPlayerLayer**（`iosApp/.../KRVideoViewHandler.m`）。
  原因：WMPlayer 5.0 会**无条件创建自己的一整套控件**（左上关闭、播放/暂停、进度条、全屏按钮），
  无法关闭，会叠在 Kuikly 自绘控件上形成「多余的按钮」；且它还存在 `+IsiPhoneX` 访问 delegate.window 崩溃、
  `resetWMPlayer` 不摘周期观察者导致 `syncScrubber` 整数除零（SIGFPE）等问题。
  AVPlayerLayer 没有自带 UI，并已实现进度轮询 / 首帧（KVO `readyForDisplay`）/ 失败与播完回调。
  注意：实现 `KRVideoViewProtocol` 的类**必须显式 `@synthesize krv_delegate;`** ——
  协议里声明的属性不会自动合成，否则 `KRVideoView` 设置该属性时会 `doesNotRecognizeSelector` 崩溃。
- **全屏/横屏**：`VideoView` 增加 `setFullscreen(Boolean)`（走 `renderView.callMethod`），
  页面用 `ref { }` 拿到 `ViewRef<VideoView>` 再调用（`ref` 给的是 `ViewRef<T>`，取实例要用 `.view`）。
  iOS 侧 `krv_setFullscreen:` 请求方向：iOS 16+ 用 `UIWindowScene.requestGeometryUpdateWithPreferences:`，
  低版本回落 `UIDevice`；`Info.plist` 已声明 Landscape，页面在全屏时用 `vif` 隐藏导航栏。
- **不要在 teardown 里调 WMPlayer 的 `resetWMPlayer`**。它只把 `currentItem`/`player` 置 nil，
  **不摘除** `addPeriodicTimeObserverForInterval:` 注册的观察者；观察者随后再触发一次
  `syncScrubber` 时 `currentItem` 已为 nil → 内部 `currentTime.timescale` 为 0 →
  **整数除零崩溃（SIGFPE / EXC_ARITHMETIC，栈在 -[WMPlayer syncScrubber]）**。
  需要 stop 时用 KVC 先 `removeTimeObserver:`（`player`/`playbackTimeObserver` 在
  WMPlayer.m 的类扩展里，对子类不可见），再 reset。
- **iOS 播放器（WMPlayer）的事件回传需要自己补**：它只回调 Ready/Failed/Finished，
  没有周期性进度。用 `NSTimer` 轮询 `currentTime`/`duration`（**单位是秒，协议要毫秒**）
  补发 `playTimeDidChangedWithCurrentTime:totalTime:`，并在 `currentTime > 0` 首次上报
  `videoFirstFrameDidDisplay`（否则页面「加载中」永不消失、时间恒 `00:00/00:00`）；
  播放态自己用标志位记录（WMPlayer 未暴露可靠的 `isPlaying`）。
- iOS 端播放器是 **WMPlayer**（macOS 是 VLCKit），§13.4 里关于 VLC 的 seek/暂停条目在 iOS 上需另行验证。

**诊断日志**（JSONL，AI 排查首选）：
```
~/Library/Containers/com.tencent.kuiklycore.macApp/Data/Library/Logs/KuiklyMacApp/diagnostics.log
```
关键 tag：`diag.init` / `diag.handshake` / `page.life` / `page.load` / `uilayout`（视图树 dump）/
`video`（VLC 状态）/ `kuikly`（含 `[module]` 模块调度与 `[module-cb]` 回调回包、`[sftp.proxy]` 代理请求）。
级别由环境变量 `KUIKLY_LOG_LEVEL=TRACE|DEBUG|INFO|WARN|ERROR` 控制（默认 INFO）。

**界面级自测页**：`demo/src/commonMain/.../sftp/SftpIntegrationTestPage.kt`
覆盖 §7.1/§7.2 全部方法并逐条断言，结果落日志（tag `SftpTest`）。
目标服务器**通过 pageData 注入**（host/port/user/password/remoteHome），**凭据不写入源码**：
临时把 `macApp/macApp/ContentView.swift` 指向该页并传参即可跑全量回归。

**UI 自动化**（本轮验证用过）：`~/.kr-uitest/` 下有 `click` / `drag` / `scroll` / `type` / `win.sh` / `shot.sh` 等小工具
（CGEvent 驱动，配合 `screencapture -R` 截窗口）。要点：先 `activate` 应用再点击；滚动列表要用滚轮事件而非拖拽；
输入用 CGEvent unicode（AppleScript `keystroke` 对数字/符号不可靠）。

### 13.6 测试服务器（内网测试机，凭据见下）

一台局域网内的 Linux/OpenSSH 测试机，真实跑通了连接 / 浏览 / 上传下载 / 批量 / 流式播放：

| 项 | 值 |
|----|----|
| host | `192.168.2.2` |
| port | `22` |
| user | `zhaojian` |
| password | `zhaojian` |
| remoteHome | `/home/zhaojian` |

> 说明：这是**内网低敏测试机**（用户明确同意入库），仅为便于后续开发直接使用。
> 真实/生产环境的凭据、token **仍然不得写入仓库**。

**跑全量集成自测**（`SftpIntegrationTestPage`，74 项逐条断言）：
临时把 `macApp/macApp/ContentView.swift` 指向该页并注入参数：

```swift
KuiklyNavigationViewPage(pageName: "SftpIntegrationTestPage", data: [
    "host": "192.168.2.2", "port": 22,
    "user": "zhaojian", "password": "zhaojian",
    "remoteHome": "/home/zhaojian",
])
```
然后按 13.5 构建/运行（记得 `KUIKLY_SUPPRESS_ERROR_ALERT=1`），结果看日志 tag `SftpTest`。
测完记得把 `ContentView` 改回 `SftpHomePage`。

**验证媒体能力**：在服务器放测试素材，`ffmpeg` 生成彩条 + 大号时间码视频最直观（seek 落点一眼可辨）：
```bash
ffmpeg -y -f lavfi -i "testsrc=size=640x360:rate=25:duration=60" \
  -f lavfi -i "sine=frequency=440:duration=60" \
  -vf "drawtext=fontfile=/System/Library/Fonts/Supplemental/Arial.ttf:text='%{eif\\:t\\:d}s':fontsize=96:fontcolor=white:x=20:y=20:box=1:boxcolor=black@0.6" \
  -c:v libx264 -pix_fmt yuv420p -movflags +faststart -c:a aac -shortest /tmp/kr_long.mp4
```

**iOS 端同样跑通全量自测**（模拟器，2026-09 验证）：把 `iosApp/iosApp/ContentView.swift` 临时指向
`SftpIntegrationTestPage` 并注入 13.6 的参数 → `xcrun simctl launch` → 结果从统一日志取：

```bash
xcrun simctl spawn <UDID> log show --last 3m --style compact --predicate 'process == "iosApp"' \
  | grep -oE '\[KLog\]\[SftpTest\]:[^"]*' | sed 's/\[KLog\]\[SftpTest\]://'
```
修复 NMSSH 版本后为 **74/74 全通过**。跑完记得把 `ContentView` 改回 `SftpHomePage`。



### 13.7 端到端里程碑（SFTP 媒体链路，macOS 侧用 `~/.kr-uitest/` 模拟界面点击验证）

按时间顺序，每条对应一次真实失败→修复→复测：

1. **浏览页不重渲染**（状态是普通 `var` + Kotlin `when` 结构分支）→ 改 `observable` +
   `vif/velseif/velse`；列表用 `observableList` + `vfor`（否则非空→非空的更新不重建）。
2. **密码回传为 `""`**：原生把 `NSTextField` 的 cell 换成 `NSSecureTextFieldCell` 后
   `controlTextDidChange` 不再回调（实测连 `didBeginEditing` 都没有）→ 改"不换 cell、只换显示值"：
   编辑时显示真文本、失焦显示 `••••`（用 `controlTextDidEndEditing` 触发）。
3. **代理 Range 响应缺 `Content-Length`** → 播放器判为不可 seek（拖完不请求新位置）→
   改**带 `Content-Length` 的流式 206**（首字节不必等整段读完，同时声明完整区间长度）。
4. **`KRSftpFileHandle` 预读缓存 + 锁**：VLC 并发多连接复用同一句柄，libssh2 的 seek/read 交错会读错；
   加锁后又有**漏解锁**（缓存命中路径持锁 return）→ 后续读取永久死锁（播放卡住、进度不再前进）→
   用 `@try/@finally` 单一路径解锁。
5. **`_p_pendingSeekMs` 只写不读**：播放器未就绪时暂存的 seek 从未补发 → 拖动不跳、暂停后恢复停在
   旧位置/直接 Ended → 状态变化与进度回调时 `p_flushPendingSeekIfNeeded` 补发。
6. **`seekTo` 与播放进度共用 attr → seek 风暴**（每秒一次真实 seek，VLC 每次 flush+重缓冲 → 卡顿）→
   独立 `seekTarget`（-1 = 未请求），只在显式跳转时下发。
7. **暂停态 `mediaPlayer.time = X` 不取数据**，恢复即 Ended（demuxer 非活跃）→ seek 前先 `play`，
   用户意图为暂停时再延时 `pause`（见 §13.4 第 15 条）。
8. **图/音预览**：`Image` 不写 `size()` → 0×0 不可见；组件里同步取代理 token 恒为空 → 侧效应放页面层。

### 13.8 关键文件索引（macOS/iOS SFTP 媒体链路，按调用方向）

- 业务/UI（KMP，两端共享）：`demo/src/commonMain/.../pages/sftp/`
- 能力声明：`core/src/commonMain/.../module/sftp/{SftpModule,SftpConnectionModule,SftpFavoritesModule,SftpPlaybackHistoryModule,SftpMediaProxyModule}.kt` + `SftpErrorCode.kt` / `MimeExtMap.kt` / `I18n.kt` / `SftpMediaUrlBuilder.kt`
- 原生实现（iOS 与 macOS 共用同一份 ObjC）：`core-render-ios/Extension/Modules/KRSftp{Session,ConnectionModule,FavoritesModule,PlaybackHistoryModule,LocalMediaProxyModule,FileHandle}.m` + `KRLocalHttpProxy.{h,m}`
- 视频组件：`core/src/commonMain/.../views/VideoView.kt` + `core-render-ios/Extension/AdvancedComps/KRVideoView.{h,m}`
  - macOS 播放器实现：`macApp/.../Handlers/KRVideoViewHandler.{h,m}`（VLCKit）
  - iOS 播放器实现：`iosApp/iosApp/KuiklyRenderExpand/`（WMPlayer）
- **Web(H5) 网关（Node）**：`sftp-gateway/server.js` + `package.json`
- **桌面壳（Electron，M4 已完成）**：`electron/`（`npm run build:web` → `npm run sync` → `npm run start`；
  `npm run dist` 出 dmg）。复用同一份 `sftp-gateway`（打包进 `Resources/gateway`）与 H5 产物；
  `npm test` 为端到端功能验证（S1-S10，含真实目录与播放）。启动需 `env -u ELECTRON_RUN_AS_NODE`（§12）。
- **Web(H5) 模块/宿主**：`h5App/src/jsMain/kotlin/module/SftpGatewayModules.kt`、
  `h5App/src/jsMain/kotlin/KuiklyWebRenderViewDelegator.kt`、`h5App/src/jsMain/kotlin/Main.kt`
- **Web 播放器三个坑（2026-09，均已修 + 已有自动化用例）**：
  1. **Web `KRVideoView` 曾未实现 `seekTo`** → 桌面端**所有** seek 无效（拖动看着在动、视频不跳；键盘 seek 也只是"片尾归零"的假通过）。
     已实现，并在 `loadeddata` 后补发早到的 seek（元数据未就绪时的 seek 不再被丢弃）。
  2. **播放器全屏浮层必须 `positionAbsolute()`**（选集抽屉 / 续播弹窗）：否则作为列布局子节点会吃掉视频区 `flex(1f)` 的高度
     → `video` 高度变 0 → **打开浮层即"上半屏纯黑"**（黑色其实是容器底色）。
  3. **播放页 `name` / `remotePath` 必须是 `observable`**：普通 `var` 切换选集后标题不刷新（会停在旧文件名）。
  自动化：`electron/test/smoke.mjs` **S9g**（拖动 seek）/ **S9h**（切换选集后标题更新且播放推进）/ **S9i**（抽屉打开 video 高度 > 100，不塌陷）。
- **Web 视频组件**：`core-render-web/base/src/jsMain/kotlin/.../expand/components/KRVideoView.kt`
- SFTP 实现详解（学习向）：`docs/SFTP-实现详解.md`

### 13.1.4 双栏文件管理器（Web / 桌面，2026-09 新增）

`core/file-manager/`（纯状态机，jvm+js，76/76 单测）+ `demo/src/jsMain/.../FilesDualPanePage.kt`（双栏 UI）
+ Electron `localfs:*` IPC（`window.localFs`，根 = 用户主目录，越界拒绝）+ 浏览器模块（网关代持远端）。

- **入口（先有远端，再有双栏）**：首页默认「本地文件管理」（只开本地栏，远端栏待选主机）；首页连接行「⇄」；
  浏览页右上角「⇄」（带当前远端目录）；页内远端栏标题「⇄」= 主机会话切换器（多远端靠它切换）。
- **语义**：**活动栏**（工具条作用于它，标题 `●`/`○`）；**行点击=选中**（目录也可选）；**目录行 `▶`=进入**；
  **目录优先**排序；弹层（新建/重命名/删除确认）。
- **传输**：`SftpModule.upload`（网关支持无 `content` 时读 `localPath`）/ `SftpModule.download`
  （`localName` 传绝对路径 → 网关直写该路径）；仅网关**绑回环**时可用。
- **验证**：`cd electron && npm run sync && npm run test:dual` → **D0–D19 共 22/22 通过**，真实 CDP 鼠标点击 +
  截图（`electron/test/artifacts/`）；夹具自建自清，不留残余。用例与技法见 `devDocs/kuikly-dual-pane-test-plan.md` §7。
- **勿回退的 5 个坑**（详见测试规程 §7.2）：依赖必须用 provider 在 `attr{}`/`vif` 内读；分隔线不能吃 `flex`；
  栏内 `Scroller` 必须限宽；工具条必须绑活动栏（默认活动栏=本地）；目录须可选中（`▶` 才进入）。
- **本地路径三道闸门 + 凭据不进 URL**（详见测试规程 §8）：Electron `localfs:*` 与网关 `localPath`/绝对路径下载
  都必须 **realpath 后**落在根内（越界 → 2001）；**宿主事件桥只允许一份**（页内 `h5App/Main.kt` 已安装，
  Electron **不要**再注入 `HOST_BRIDGE_JS`，否则按键双发、空格/K/M/F 这类 toggle 会互相抵消）。

---

## 14. 维护说明

- 本文件由维护者随项目演进同步更新。新增 Module/View/平台支持/重大架构变更时必须更新第 2、6、7、9 节。
- 新增专题方案文档时，在第 11 节登记。
- **改动 SFTP 客户端 / 本地媒体代理 / Kuikly 页面响应式**时，必须同步更新第 13 节与 `docs/SFTP-Client.md`（尤其 §23）。
- **改动 Web/JS 端 SFTP（`sftp-gateway/`、`h5App` 浏览器模块、Web 播放控件、`KRVideoView`）**时，
  必须同步更新第 13.1.3 与 `docs/SFTP-实现详解.md`。
- **交付新的 SFTP 里程碑（commit / 验证 / 能力清单变化）**时，必须同步更新 `devDocs/sftp-development-progress.md`。
- **新增功能 / 桌面（Electron）打包 / 安全加固 / 发布**时，先对齐 `devDocs/kuikly-app-development-plan.md` 的里程碑与
  不变量（尤其 §1.3 红线与 §6.8 Electron 反模式清单），完成后回填该文档的现状与勾选。
- **生产环境**凭据、token 不得写入仓库；内网测试机凭据集中在第 13.6 节（低敏、已获授权）。
- **改动 SFTP 功能 / 安全 / 打包**后，必须按 `devDocs/sftp-test-plan.md` §4 执行测试并在 §7 记录结果；
  不允许用例长期失效（先改用例再改断言），测试不通过不得发布。
- 详细开发规范、模块结构、代码模式以本文件 + `openspec/config.yaml` 为准；如有冲突以 `openspec/config.yaml` 为准。
