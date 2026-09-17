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
| 跨平台 SFTP 客户端 + 流式视频播放 | `docs/SFTP-Client.md` | SftpModule + SftpFavoritesModule + 本地 HTTP 代理 + VideoView 复用；完整文件管理（CRUD/权限/批量）+ 文件与文件夹收藏 + 点击即流式播放；四端落地方案 |
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
- ❌ 在 Web/MiniApp 假设有 TCP/SSH 能力 → 浏览器沙箱限制，需要后端网关。

---

## 13. 维护说明

- 本文件由维护者随项目演进同步更新。新增 Module/View/平台支持/重大架构变更时必须更新第 2、6、7、9 节。
- 新增专题方案文档时，在第 11 节登记。
- 详细开发规范、模块结构、代码模式以本文件 + `openspec/config.yaml` 为准；如有冲突以 `openspec/config.yaml` 为准。
