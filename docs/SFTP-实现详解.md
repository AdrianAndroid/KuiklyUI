# KuiklyUI 跨平台 SFTP 客户端实现详解

> 这是一份「学习用」的深度实现文档，目标是把一个真实的跨端复杂功能（SFTP 客户端 + 流式视频播放）
> 从**架构 → 桥接机制 → 共享能力层 → 各端原生实现 → 流式播放 → UI → 测试**全部讲透。
>
> 关联资料：
> - `docs/SFTP-Client.md` —— 设计与 spec（3758 行，讲「要做什么」）
> - `AGENTS.md` §13 —— 实现现状、踩坑与验证矩阵（讲「踩过什么坑」）
> - `devDocs/sftp-impl-plan.md` —— 实施计划
>
> 本文讲「**代码是怎么写出来的、为什么这么写**」。建议第一次阅读按章节顺序；
> 只想做某件事时直接看 §10 的扩展指南。

---

## 目录

- [0. 学习导览](#0-学习导览)
- [1. 项目背景与六端能力矩阵](#1-项目背景与六端能力矩阵)
- [2. 总体架构](#2-总体架构)
- [3. Kuikly Module 桥接机制（基础必读）](#3-kuikly-module-桥接机制基础必读)
- [4. 共享能力层（commonMain）详解](#4-共享能力层commonmain详解)
- [5. 流式播放核心：本地 HTTP 代理](#5-流式播放核心本地-http-代理)
- [6. 平台实现层](#6-平台实现层)
- [7. UI 层](#7-ui-层)
- [8. 测试与验证](#8-测试与验证)
- [9. 跨端一致性硬规则与踩坑总表](#9-跨端一致性硬规则与踩坑总表)
- [10. 扩展指南](#10-扩展指南)
- [11. 文件索引](#11-文件索引)

---

## 0. 学习导览

这个项目值得学的点，不是「SFTP 协议怎么用」（那是库的事），而是：

1. **一份 KMP 代码怎么在六端落地**：共享什么、各端写什么、边界怎么划。
2. **Kuikly Module 桥接机制**：参数/回包约定、原子二进制通道、线程模型、模块解析。
3. **一个「不能直接实现」的需求怎么绕过去**：浏览器/播放器只认 HTTP，而文件在 SFTP 上 —— 用本地 HTTP 代理把 Range 请求翻译成 `lseek + read`，实现边下边播。
4. **跨端一致性的工程纪律**：桩实现必须显式失败、错误码统一、侧效应分层。
5. **一组真实的坑**：Kuikly 响应式、原生控件尺寸、pod install、播放器 seek 语义、线程安全。

三层阅读路径：

| 你的目标 | 建议路径 |
|---|---|
| 理解整体设计 | §1 → §2 → §5.1 → §9.1 |
| 要改/加一个 SFTP 能力 | §3 → §4 → §10.1 |
| 要修跨端 bug | §5.7 → §9 → §6.x 对应端 |
| 要学 Kuikly | §3 → §7.3 → §9.2 |

---

## 1. 项目背景与六端能力矩阵

### 1.1 需求

在 KuiklyUI 里做一个跨端 SFTP 客户端：

- 连接管理（保存/编辑/删除连接，密码或密钥认证）
- 文件浏览（list / stat / 目录进出 / 符号链接）
- 文件管理（mkdir / rm / rename / move / copy / chmod / chown / setMtime / 批量任务）
- 上传下载（带进度、断点续传语义）
- 收藏与播放历史（跨会话持久化）
- **流式视频播放**：不先下载整个文件，边下边播，支持 seek
- 文档预览（文本 / Markdown / HTML / 图片 / PDF）

### 1.2 为什么基于 KuiklyUI

- 业务/UI 100% 共享，只有「文件系统 + SSH/SFTP + 本地 HTTP 服务」这些平台原语需要各端各写一份。
- 复用 `VideoView`、`Image`、`Scroller`、`RouterModule` 等已有组件，不改 DSL。
- 六端（Android / iOS / macOS / HarmonyOS / Web / 小程序）尽量一套 UI。

### 1.3 六端真实能力矩阵（2026-09 实测，不是「文件是否存在」）

| 端 | SSH/SFTP 协议栈 | 本地 HTTP 代理 | 状态 |
|----|----------------|----------------|------|
| **Android** | JSch 0.1.55 | NanoHTTPD 2.3.1 | ✅ 可用：74/74 集成自测 ×10 轮 + ExoPlayer 经代理播放 |
| **iOS** | NMSSH 2.7.2（libssh2 1.10.0）| GCDWebServer 3.5.4 | ✅ 可用：74/74 + AVPlayer 播放 |
| **macOS** | 同 iOS（共用 ObjC）| GCDWebServer 3.5.4 | ✅ 可用：界面操控验证（播放/拖动 seek/暂停恢复）|
| **HarmonyOS** | libssh2（自 vendored）+ mbedTLS | ❌ 桩 | ⚠️ 核心文件操作已实现，**代理与播放器未实现** |
| **Web (H5)** | 浏览器无 TCP/SSH | ❌ | ➖ 架构受限，需后端网关 |
| **MiniApp** | 同上 | ❌ | ➖ 架构受限 |

> 关键认知：**跨端功能的「完成」不是编译通过，而是每一端都跑过真实服务器**。
> Android 与 iOS/macOS 是「能用」的端；HarmonyOS 是「核心已实现、媒体链路缺失」；
> Web/小程序是「物理上做不到」（浏览器不给原始 socket），只能走后端网关。

### 1.4 代码目录速查

```
core/src/commonMain/kotlin/com/tencent/kuikly/core/module/sftp/
├── SftpModule.kt                  # 主 Module（19 个方法）
├── SftpConnectionModule.kt        # 连接配置 CRUD
├── SftpFavoritesModule.kt         # 收藏 CRUD
├── SftpPlaybackHistoryModule.kt   # 播放历史 CRUD + LRU
├── SftpMediaProxyModule.kt        # 本地媒体代理控制
├── SftpModel.kt                   # 全部数据模型 + 序列化
├── SftpErrorCode.kt               # 错误码 + SftpError
├── MimeExtMap.kt                  # 扩展名 → MIME
├── EncodingDetector.kt            # 文本编码嗅探
├── SftpMediaUrlBuilder.kt         # 播放 URL / Range 解析
└── I18n.kt                        # 中英文案

demo/src/commonMain/kotlin/com/tencent/kuikly/demo/pages/sftp/
├── SftpBasePager.kt               # 基类：注册 Module + 主题
├── SftpHomePage.kt                # 首页（连接/收藏/历史 Tab）
├── SftpBrowserPage.kt             # 文件浏览
├── SftpPlayerPage.kt              # 视频播放
├── SftpConnectEditPage.kt         # 新建/编辑连接
├── SftpFilePropsPage.kt           # 文件属性
├── SftpFavoritesPage.kt / SftpHistoryPage.kt
├── SftpBatchProgressDialog.kt
├── SftpIntegrationTestPage.kt     # 74 项端到端自测
├── theme/                         # 颜色 token + 无障碍
└── viewer/                        # 预览分发 + 6 种 viewer

core-render-android/.../expand/module/
├── KRSftpModule.kt / KRSftpClient.kt
├── KRSftp{Connection,Favorites,PlaybackHistory}Module.kt
├── Sftp{Connection,Favorites,PlaybackHistory}Storage.kt
├── LocalHttpProxyServer.kt / KRLocalMediaProxyModule.kt
└── SftpEntry.kt / SftpErrorFormatter.kt

core-render-ios/Extension/Modules/
├── KRSftpModule.{h,m} / KRSftpSession.{h,m} / KRSftpFileHandle.{h,m}
├── KRSftp{Connection,Favorites,PlaybackHistory}Module.{h,m}
└── KRLocalHttpProxy.{h,m} / KRLocalMediaProxyModule.{h,m}

core-render-ohos/src/main/cpp/libohos_render/expand/modules/sftp/
├── KRSftpModule.cpp / KRSftpSession.cpp / KRSftpFileHandle.cpp
├── KRSftp{Connection,Favorites,PlaybackHistory}Module.cpp
├── SftpJsonStore.cpp / SftpErrorFormatter.cpp / KRSftpInternal.h
└── KRLocalHttpProxy.cpp          # stub，且未进 CMake
```

---

## 2. 总体架构

### 2.1 四层分层

```
┌──────────────────────────────────────────────────────────────────────┐
│ ① 业务与 UI 层（100% 共享，commonMain）                                │
│    页面：Home / Browser / Player / Viewer / Props / Favorites / History│
│    组件：列表 / Tab / 表单 / 对话框 / 进度条                            │
│    纯函数：MimeExtMap / SftpMediaUrlBuilder / EncodingDetector /       │
│            formatTime / formatSize / SftpErrorCode                     │
│    状态：observable / observableList                                    │
├──────────────────────────────────────────────────────────────────────┤
│ ② 能力声明层（100% 共享，commonMain）                                  │
│    5 个 Module：Sftp / Connection / Favorites / PlaybackHistory /      │
│                 MediaProxy                                             │
│    数据模型 + 序列化（toJson/fromJson）                                 │
│    只声明「有什么能力、参数是什么」，不关心谁实现                        │
├──────────────────────────────────────────────────────────────────────┤
│ ③ 原语桥接层（Kuikly Module 机制，各端实现同名类）                      │
│    模块名 == 原生类名；NSClassFromString / 注册表解析                    │
│    入参 JSON 字符串（或数组），出参 JSON；二进制走原子 ByteArray 通道     │
├──────────────────────────────────────────────────────────────────────┤
│ ④ 平台实现层（各端各写一份）                                            │
│    SSH/SFTP：JSch / NMSSH(libssh2) / libssh2(C++) / 后端网关            │
│    本地 HTTP 代理：NanoHTTPD / GCDWebServer / （OHOS 无）               │
│    播放器：ExoPlayer / AVPlayer / VLCKit / （OHOS 无）                  │
└──────────────────────────────────────────────────────────────────────┘
```

**依赖方向铁律**：`core/`（含 `module/sftp/*`）是纯 KMP，**禁止**依赖任何 `core-render-*`。
所以 commonMain 只能声明 Module 名与 JSON 协议，具体实现靠运行时按名字解析。
这正是「本地代理必须走 Module 而不是 expect/actual」的根本原因（见 §2.3）。

### 2.2 一次文件浏览的数据流

```
SftpBrowserPage.created()
  → acquireModule<SftpModule>("KRSftpModule")     // ② 能力声明
    → SftpModule.connect(param)
      → asyncToNativeMethod("connect", param.toJson(), cb)   // ③ 桥
        → [Android] KRSftpModule.connect()  （子线程）
            → executeOnSubThread { KRSftpClient.connect(params) }  // ④ JSch
              → 回调 mapOf("sessionId" to "sftp-1") → cb
        → [iOS] KRSftpModule connect: （串行队列）
            → KRSftpSession connect: → NMSSHSession → 回调 @{@"sessionId"}
        → [OHOS] KRSftpModule::Connect（detach 线程）
            → KRSftpSession::Connect → libssh2 → 回调 {"sessionId"}
  → 回到 Kotlin：data.str("sessionId") → sessionId
  → SftpModule.list(sid, path) → ... 同理
  → entries 写入 observableList → vfor diff → 刷新列表
```

要点：
- UI 永远不直接碰原生；只通过 Module 的**异步回调**。
- 所有 IO 都在原生子线程执行，回调回到 Kotlin/JS 线程。
- 状态更新必须写到 `observable`，否则异步回包不触发重渲染（§7.3）。

### 2.3 关键设计决策：为什么统一走 Module，而不是 expect/actual

历史上本地媒体代理有两套并行机制：
`LocalMediaProxyApi`（expect/actual，6 个平台文件）与 `SftpMediaProxyModule`（Module）。
现已**删除 expect/actual 版本，统一用 Module**，理由：

| 维度 | expect/actual | Kuikly Module（现方案） |
|------|---------------|------------------------|
| 与其它 SFTP 能力一致 | ❌ 独树一帜 | ✅ 与 connect/list/stat 同构 |
| 加一端是否要动 core | ✅ 每端都要加 actual，漏一端编译不过（双刃剑）| ❌ 不需要 |
| 能否访问 Pager 上下文 | ❌ 拿不到 | ✅ 可以 |
| 原生实现耦合方式 | 必须在 core 声明符号，必须 cinterop | 只需同名类，运行时解析 |
| 统一日志/排查 | 无 | ✅ 模块调度与 callback 统一落日志 |

例外：需要**在非 Pager 上下文**（全局后台线程）调用、或返回**同步值**且对性能极敏感的场景，才考虑 expect/actual。
本地代理两者都不满足（要按 Page 注册 token、天生异步），故归 Module。

### 2.4 五个 Module 的职责与生命周期

| Module | Kotlin 类 | 模块名（== 原生类名）| 生命周期 | 职责 |
|---|---|---|---|---|
| SFTP 主 | `SftpModule` | `KRSftpModule` | **绑定 Page**（`createExternalModules` 每页 new）| 连接、文件操作、随机读、批量 |
| 连接配置 | `SftpConnectionModule` | `KRSftpConnectionModule` | **全局单例** | 保存的连接 CRUD + 删连接级联清理 |
| 收藏 | `SftpFavoritesModule` | `KRSftpFavoritesModule` | **全局单例** | 收藏 CRUD / 搜索 / 判重 |
| 播放历史 | `SftpPlaybackHistoryModule` | `KRSftpPlaybackHistoryModule` | **全局单例** | 进度 upsert / 查询 / 2000 条 LRU |
| 媒体代理 | `SftpMediaProxyModule` | `KRLocalMediaProxyModule` | **全局单例** | 启代理端口、注册/注销 token |

- 主 Module 绑定 Page：`SftpBasePager.createExternalModules()` 里 `map[SftpModule.MODULE_NAME] = SftpModule()`。
- 单例模块：Android 用 `object ...Holder` 返回同一实例，iOS/OHOS 本来就是进程级静态存储。
- **`OnDestroy` 故意不清理会话**：会话是显式生命周期（由调用方 `disconnect`），
  若 Page 销毁时全局 `ShutdownAll`，会连带断开其它 Page（如播放页）持有的会话。

---

## 3. Kuikly Module 桥接机制（基础必读）

### 3.1 Module 的最小形态

```kotlin
class SftpModule : Module() {
    override fun moduleName(): String = MODULE_NAME        // "KRSftpModule"
    fun connect(param: SftpConnectParam, callback: (String?, SftpError?) -> Unit) {
        asyncToNativeMethod(METHOD_CONNECT, param.toJson()) { data ->
            ...
        }
    }
    companion object {
        const val MODULE_NAME = ModuleConst.SFTP
        const val METHOD_CONNECT = "connect"
    }
}
```

模块名常量集中在 `core/.../module/ModuleConst.kt`：

```kotlin
const val SFTP = "KRSftpModule"
const val SFTP_FAVORITES = "KRSftpFavoritesModule"
const val SFTP_PLAYBACK_HISTORY = "KRSftpPlaybackHistoryModule"
const val SFTP_CONNECTION = "KRSftpConnectionModule"
const val SFTP_MEDIA_PROXY = "KRLocalMediaProxyModule"
```

### 3.2 三端的注册/解析方式

| 端 | 机制 | 代码位置 |
|----|------|---------|
| Android | 显式 `moduleExport(name) { factory }` | `KuiklyRenderViewBaseDelegator.registerModule` |
| iOS/macOS | 模块名即类名，运行时 `NSClassFromString` 解析 | 无需注册（`KRLocalMediaProxyModule.m` 注释明确）|
| HarmonyOS | `RegisterModuleCreator(name, factory)` | `libohos_render/.../ModulesRegisterEntry.h` |

Android 注册片段：

```kotlin
// KuiklyRenderViewBaseDelegator.kt（registerModule ~L538）
moduleExport(KRSftpModule.MODULE_NAME) { KRSftpModule() }
moduleExport(KRSftpFavoritesModule.MODULE_NAME) { KRSftpFavoritesModuleHolder.instance }
moduleExport(KRSftpPlaybackHistoryModule.MODULE_NAME) { KRSftpPlaybackHistoryModuleHolder.instance }
moduleExport(KRSftpConnectionModule.MODULE_NAME) { KRSftpConnectionModuleHolder.instance }
moduleExport(KRLocalMediaProxyModule.MODULE_NAME) { KRLocalMediaProxyModule() }
```

OHOS 注册片段：

```cpp
IKRRenderModuleExport::RegisterModuleCreator(kuikly::module::KRSftpModule::MODULE_NAME, [] {
    return std::make_shared<kuikly::module::KRSftpModule>();
});
// 另有 Favorites / PlaybackHistory / Connection 三个；没有 Proxy 模块
```

> 记忆点：**模块名就是原生类名**。加一个 Module 时，Kotlin 常量、原生类名、注册名必须三处一致，
> 否则运行时解析不到（iOS DEBUG 下可能是 `NSAssert` 崩溃）。

### 3.3 参数与回包约定

- **入参**：统一 JSON。Kotlin 侧用 `com.tencent.kuikly.core.nvi.serialization.json.JSONObject` 构造后
  直接传给 `asyncToNativeMethod`；原生侧解析 JSON 字符串。
- **出参**：统一 JSON。Kotlin 侧用 `data?.str("error")`、`data?.arrOrNull("entries")` 读取。
- **错误**：统一放在 `error` 字段，值是 **JSON 字符串**：
  `{"code":Int,"msg":String,"detail":String?}`，由 `SftpError.fromJson` 解析。
  `msg` 是 i18n key；`detail` 只用于排查，不展示给用户。

**关键陷阱（Android）**：Kotlin 模块回调**不能直接塞裸 `JSONObject` / `JSONArray`**，
桥会读不到（症状：列表恒为空、字段全空）。必须：

```kotlin
// 错误做法
callback?.invoke(mapOf("entries" to jsonArray))
// 正确做法：包进 JSONObject 再 toMap()（toMap 会把嵌套数组转 List）
val payload = JSONObject().apply { put("entries", jsonArray) }
callback?.invoke(payload.toMap())
```

### 3.4 原子二进制通道（随机读的核心）

普通 Module 回包是 JSON，但随机读要传 `ByteArray`。走 JSON 就得 base64，开销大。
Kuikly 提供**原子通道**：把 `[meta, ByteArray]` 作为数组返回，框架检测到数组里有 ByteArray
就**不做 JSON 序列化**，直接以 native 数组 + ArrayBuffer 传递。

Kotlin 侧签名：

```kotlin
fun read(fileHandleId: String, offset: Long, length: Int,
         callback: (data: ByteArray?, error: SftpError?) -> Unit) {
    asyncToNativeMethod(METHOD_READ, arrayOf(fileHandleId, offset, length)) { result ->
        val arr = result as? Array<Any?>              // [meta, ByteArray]
        if (arr == null || arr.size < 2) {
            callback(null, SftpError.of(SftpErrorCode.PROTOCOL_ERROR)); return@
        }
        val meta = arr[0] as? JSONObject
        val bytes = arr[1] as? ByteArray
        val err = meta?.str("error")
        if (!err.isNullOrEmpty()) callback(null, SftpError.fromJson(err))
        else callback(bytes, null)
    }
}
```

各端实现（返回 `[meta, ByteArray]`）：

| 端 | 成功 | 失败 |
|----|------|------|
| Android | `arrayOf(JSONObject{"ok":true}, bytes)` | `arrayOf(JSONObject{"error":...}, null)` |
| iOS | `@[@{@"ok":@YES}, bytes]` | `@[@{@"error":...}, [NSNull null]]` |
| OHOS | `[{"ok":true}, ByteArray]`（`KRRenderValue::Make` 识别 ByteArray）| 见下方注意 |

> OHOS 注意：`KRSftpFileHandle::Read` 在「句柄不存在 / 会话 broken / 读错误」时返回的是**裸 Map**，
> 不是两元素数组，Kotlin 侧 `result as? Array<Any?>` 失败 → 只能报 `PROTOCOL_ERROR`，丢失具体原因。
> 只有 C++ 抛出异常被 `KRSftpModule::Read` catch 时才会包成两元素数组。这是一个已知一致性缺陷。

### 3.5 线程模型（三端不同，务必注意）

| 端 | 工作线程 | 串行化手段 |
|----|---------|-----------|
| Android | `KuiklyRenderAdapterManager.krThreadAdapter.executeOnSubThread`，demo 是**固定 2 线程池** | `ConcurrentHashMap` + session 内 `Any` 锁保护 channel 列表；**不串行化 IO** |
| iOS/macOS | 每个 Module 一条 **DISPATCH_QUEUE_SERIAL** | 整个 module 的调用串行 |
| OHOS | 每次 `CallMethod` **detach 一个 std::thread** | `SessionHandle::io` 为 `recursive_mutex`，libssh2 操作串行 |

**为什么必须串行**：libssh2 的 `LIBSSH2_SESSION` / JSch 的 `ChannelSftp` 都不是线程安全的。
并发调用会导致「刚 mkdir 立刻 list 看不到」、握手偶发失败、随机读错位。

iOS 端额外把每个 Module 的**存储模块**也串行到独立队列（favorites/history/connection），
因为它们是「读-改-写同一份 JSON」，并发会丢更新。

### 3.6 新增一个能力的四步（缺一不可）

1. `core/.../module/sftp/XxxModule.kt` 用 `asyncToNativeMethod` 声明方法。
2. `ModuleConst.kt` 加模块名常量（如果新增模块）。
3. 各端实现**同名类**并分发方法：
   - Android：`override fun call(method, params, callback)` 分派。
   - iOS：`- (void)hrv_callWithMethod:` 或按方法名实现 `- (void)method:(NSDictionary *)args`。
   - OHOS：`CallMethod(sync, method, params, callback)` 分派。
4. 页面 `acquireModule(XxxModule.MODULE_NAME)`，并在 `SftpBasePager.createExternalModules()` 注册。

---

## 4. 共享能力层（commonMain）详解

### 4.1 数据模型（`SftpModel.kt`）

#### SftpConnectParam —— 运行时连接入参

```kotlin
data class SftpConnectParam(
    val host: String,
    val port: Int = 22,
    val user: String,
    val password: String? = null,
    val privateKey: String? = null,        // PEM 内容（不是路径）
    val passphrase: String? = null,
    val knownHosts: String? = null,
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val connectTimeoutMs: Int = 15000,
    val readTimeoutMs: Int = 30000,
    val keepAliveIntervalSec: Int = 15,
    val idleDisconnectSec: Int = 1800,
    val serverEncoding: String = "UTF-8",
    val compression: Boolean = false,
    val maxConcurrentChannels: Int = 4
)
```

`toJson()` 是各端解析的**契约**。字段名尤其重要——
OHOS 端曾把 `user`/`privateKey` 误读成 `username`/`privateKeyPath`，导致连接**恒失败**（见 §9）。
`fromJson` 缺省值要与声明一致。

> 各端实际读取的字段不同，很多是「声明了但没实现」：
> - Android 读：host/port/user/password/privateKey/passphrase/connectTimeoutMs/keepAliveIntervalSec/compression
> - iOS 读：host/port/user/password/privateKey/passphrase/connectTimeoutMs
> - OHOS 读：host/port/user/password/privateKey/passphrase/connectTimeoutMs/readTimeoutMs
> - 三端都**未实现**：knownHosts、authMethod、idleDisconnectSec、serverEncoding、maxConcurrentChannels

#### 枚举

```kotlin
enum class AuthMethod { PASSWORD, PUBLIC_KEY, AGENT }
enum class OverwriteMode { OVERWRITE, SKIP, FAIL, RENAME_APPEND_SUFFIX }  // 各端均未实现，一律覆盖
enum class SftpFavoriteSortBy { STARRED_AT, NAME, CONNECTION_LABEL, MTIME }
enum class SortOrder { ASC, DESC }
enum class SftpBatchAction { DELETE, MOVE, COPY, DOWNLOAD }
```

#### SftpEntry —— 目录项

字段：`name / path / isDir / size / mtime / permission / uid / gid / owner / group / mimeHint / isSymlink / symlinkTarget / followsTarget`。

- `toJson()` 对 `uid/gid` 只在 `>=0` 时输出；`isSymlink` 相关字段只在是真链接时输出。
- `listFromJson` 直接 `optJSONObject(it)!!`，要求数组元素都是对象。

各端能填的字段不同：
- iOS：`isSymlink` 恒 `false`（NMSFTPFile 不暴露），无 uid/gid。
- Android：有 uid/gid，`followsTarget = isSymlink && !isDir`（启发式），无 symlinkTarget。
- OHOS：能读链接目标（`libssh2_sftp_symlink_ex` READLINK），字段最全。

#### SftpPlaybackRecord —— 播放历史

```kotlin
val progressPercent: Int get() =
    if (duration <= 0L) 0 else ((position.toDouble() / duration * 100).toInt()).coerceIn(0, 100)

companion object {
    fun buildId(connectionId: String, remotePath: String): String =
        "$connectionId|$remotePath".hashCode().toString(16)
}
```

#### SftpConnection —— 持久化连接配置

与 `SftpConnectParam` 区分：后者是运行时入参，前者是持久化记录，`id` 作为收藏/历史的关联键。
`toConnectParam()` 转运行时参数；`buildId(host, port, user)` 合成稳定 id。

#### SftpBatchTask / SftpCopyResult

```kotlin
data class SftpBatchTask(val sessionId: String, val action: SftpBatchAction,
                         val items: List<String>, val targetDir: String? = null,
                         val localDir: String? = null)
data class SftpCopyResult(val success: Boolean, val copiedCount: Int,
                          val failedCount: Int, val errors: List<String> = emptyList())
```

### 4.2 SftpModule API 全表

| 方法 | 参数（Kotlin）| 回调 | 原生回包 key |
|---|---|---|---|
| `connect` | `SftpConnectParam` | `(sessionId, error)` | `sessionId` |
| `disconnect` | `sessionId` | `(error)` | `ok` |
| `list` | `sessionId, remotePath, includeHidden=true, offset=0, limit=10000` | `(entries, hasMore, error)` | `entries`, `hasMore` |
| `stat` | `sessionId, remotePath, followSymlink=true` | `(entry, error)` | `entry` |
| `openRead` | `sessionId, remotePath` | `(fileHandleId, error)` | `fileHandleId` |
| `read` | `fileHandleId, offset, length` | `(ByteArray, error)` | 原子 `[meta, bytes]` |
| `close` | `fileHandleId` | `(error)` | `ok` |
| `download` | `sessionId, remotePath, localName, offset=0, overwrite` | `(progress, path, error)` | `progress`, `path` |
| `upload` | `sessionId, localPath, remotePath, offset=0, overwrite` | `(progress, success, error)` | `progress`, `success` |
| `mkdir` | `sessionId, remotePath, recursive=true` | `(success, error)` | `ok` |
| `rm` | `sessionId, remotePath, recursive=false` | `(success, error)` | `ok` |
| `rename` | `sessionId, oldPath, newPath` | `(success, error)` | `ok` |
| `move` | `sessionId, srcPath, destDir` | `(success, error)` | `ok` |
| `copy` | `sessionId, srcPath, destPath` | `(SftpCopyResult, error)` | `result` |
| `chmod` | `sessionId, remotePath, mode`（八进制字符串）| `(success, error)` | `ok` |
| `chown` | `sessionId, remotePath, uid, gid` | `(success, error)` | `ok` |
| `setMtime` | `sessionId, remotePath, mtime, atime` | `(success, error)` | `ok` |
| `batchTask` | `SftpBatchTask` | `(progress, success, error)` | `progress`, `success` |
| `cancelBatchTask` | `taskId` | `(error)` | `ok` |

> `rename` 与 `move` 的区别：`rename` 传完整新路径；`move` 传目标**目录**，底层语义是
> 「rename 到 destDir/原文件名」。OHOS 的 `Rename` 同时接受两组 key（`oldPath/newPath` 与 `srcPath/destPath`）。

### 4.3 SftpConnectionModule

方法：`add / update / remove / list / get / touchLastUsed`。

- `add`：id 为空时原生生成；label 重复时各端行为不同（Android UUID，OHOS 追加 `(2)`）。
- `remove`：原生侧**级联**清收藏 + 历史（按 connectionId）。
- `list`：按 `lastUsedAt DESC`。
- `touchLastUsed`：连接成功后调用，更新排序。

### 4.4 SftpFavoritesModule

方法：`add / remove / removeByConnection / list / isFavorited / update / search`。

- `isFavorited(connectionId, remotePath)` 返回 id（空串表示未收藏），**不当作错误**。
- `list` 支持 `connectionId` 过滤 + 排序。
- `update` 只允许改 `note` 和 `iconOverride`（白名单）。
- `search` 对 name / remotePath / connectionLabel 做不区分大小写子串匹配。

### 4.5 SftpPlaybackHistoryModule

方法：`upsert / get / listByDirectory / listByConnection / remove / clearByConnection / markCompleted`。

- 稳定主键：`connectionId + "::" + remotePath`（OHOS）/ `buildId(connectionId, remotePath)`（Android/iOS）。
- 容量 2000 条 LRU，按 `lastPlayedAt` 淘汰（在每次 upsert 后裁剪）。
- `listByConnection("")` 在 iOS 上表示「全连接」——首页历史 Tab 依赖这一点。
- `markCompleted`：`completed=true` 且 `position=duration`（snap 到结尾）。

### 4.6 SftpMediaProxyModule

```kotlin
fun startOrGetPort(callback: (Int) -> Unit)                      // 失败返回 0
fun registerToken(sessionId, remotePath, totalSize, cb: (String) -> Unit)  // 失败空串
fun unregisterToken(token, cb)
fun stop(cb)
```

### 4.7 错误模型

`SftpErrorCode` 分段：

| 段 | 含义 | 例子 |
|---|---|---|
| 1001-1099 | 连接 | `NETWORK_UNREACHABLE(1001)`、`CONNECTION_TIMEOUT(1002)`、`AUTH_FAILED(1003)`、`HOST_KEY_MISMATCH(1004)` |
| 2001-2099 | 文件操作 | `PERMISSION_DENIED(2001)`、`NO_SUCH_FILE(2003)`、`DIR_NOT_EMPTY(2005)` |
| 3001-3099 | 协议 | `PROTOCOL_ERROR(3001)` |
| 4001-4099 | 预览 | `PDF_PASSWORD_REQUIRED(4001)` 等 |
| 5001-5099 | 代理 | `PROXY_START_FAILED(5001)`、`TOKEN_EXPIRED(5003, http=410)` |
| 9001-9999 | 通用 | `CANCELLED(9001)`、`TIMEOUT(9002)`、`NOT_IMPLEMENTED(9999)` |

`SftpError.fromJson(error: String?)` 兼容两种格式：
标准 JSON `{"code","msg","detail"}`，或旧版纯文本（fallback 成 `UNKNOWN` + 原文本做 msg）。

> ⚠️ **各端映射并不完全一致**（学习重点，见 §9.4）：
> - iOS：2003 = 文件不存在，2001 = 权限 —— 与 common 枚举一致。
> - Android：**2001 = 文件不存在，2002 = 权限** —— 与枚举相反！
> 加新端或改映射时必须以 common 枚举为准，否则 UI 文案会串。

### 4.8 辅助工具

- `MimeExtMap`：约 100 个扩展名 → MIME，提供 `isVideo/isAudio/isImage/isText/isMarkdown/isHtml/isPdf/isSvg/isArchive`。
  **错误 MIME 会导致 iOS AVPlayer 拒绝播放**（视频必须是 `video/mp4` 之类）。
- `EncodingDetector`：探测顺序 = UTF-8/UTF-16 BOM → 前 4KB null byte 占比 >1% 判二进制 →
  严格 UTF-8 校验 → GBK 启发式 → Latin-1。`BINARY_NULL_RATIO_THRESHOLD = 0.01`。
- `SftpMediaUrlBuilder`：`buildPlayUrl(port, token, fileName)` → `http://127.0.0.1:<port>/<token>/<name>`；
  `parseRange`、`buildContentRange`、`safeTokenForLog` / `safeUrlForLog`（日志脱敏）。
- `I18n`：中英双语，`t(key, vararg args)` 手动替换 `%s/%d`（commonMain 无 `String.format`）。
  找不到 key 时**回显 key 本身**——界面出现 `sftp.xxx.yyy` 就是漏了文案。

---

## 5. 流式播放核心：本地 HTTP 代理

这是整个项目最有技术含量的部分，也是「跨端 + 跨协议」的经典解法。

### 5.1 问题：为什么不能直接播放

- 播放器（ExoPlayer / AVPlayer / VLC）只认 `http(s)://` / `file://`，不认 `sftp://`。
- SFTP 是**随机访问**协议，但播放器要做 HTTP Range（拖动进度条、读 MP4 的 moov 尾部）。
- 如果先 `download` 整个文件再播：大文件等待久、占磁盘、无法快速 seek。
- 浏览器/小程序更没有原始 socket，连 SSH 都建不了。

### 5.2 方案：本地 HTTP 代理，把 Range 翻译成 `lseek + read`

```
┌────────────┐  HTTP GET /token/name        ┌──────────────────┐
│  VideoView  │  Range: bytes=start-end  ──►│  LocalHttpProxy  │
│ (播放器)    │◄──  206 + Content-Length ───│  (127.0.0.1:1808x)│
└────────────┘      + Content-Range          └────────┬─────────┘
                                                       │ read(offset, length)
                                                       ▼
                                              ┌──────────────────┐
                                              │  SftpFileHandle  │
                                              │  seek64 + read    │
                                              └────────┬─────────┘
                                                       ▼
                                                 远端 SFTP 文件
```

URL 形如 `http://127.0.0.1:18080/<token>/<fileName>`。
token 把「一次播放」映射到 `(sessionId, remotePath, totalSize)`，并**懒打开**远端读句柄。
（token 长度各端不同：Android 16 随机字节 → 32 hex；iOS 32 随机字节 → 64 hex；公共侧不关心长度。）

三端键路径完全一致：
`SftpMediaProxyModule.startOrGetPort` → `registerToken(sessionId, remotePath, size)` → 拼 URL →
`VideoView.src(url)` → 播放器发 Range → 原生代理 → `SftpFileHandle.read(offset, len)`。

### 5.3 Token 生命周期

1. 页面进入播放页：`startOrGetPort`（幂等，只在首次真正启动 server），再 `registerToken`。
2. `registerToken` **不占用 SFTP 资源**（`fileHandleId = null`），首次 HTTP 请求才 `openRead`。
3. 每次请求命中 token 就**续期**（TTL 2 小时）。
4. 页面离开：`unregisterToken(token)` → 关闭 SFTP 句柄。
5. App/业务结束：`stop` → 停 server 并释放所有句柄。

端口分配：固定尝试 `18080..18089`，第一个能绑定的胜出（§21.3.1）。
三端都用这个范围（集成自测页也断言 `port in 18080..18089`）。

### 5.4 Range 解析与 206 响应（三端对照）

**公共语义**：
- 支持 `bytes=start-end` / `bytes=start-` / `bytes=-suffix`；多段 Range 不支持（退化/忽略）。
- 无 Range 时按 200 返回。
- **必须带 `Content-Length`**：缺它播放器会把该输入判为「不可 seek」
  （症状：拖进度条后不请求新位置、暂停恢复直接 Ended）。
- 带 `Content-Range: bytes start-end/total`、`Accept-Ranges: bytes`、`Cache-Control: no-store`。

| 端 | 实现 | 单次分片上限 | 无 Range 小文件 | 越界 |
|---|---|---|---|---|
| Android | NanoHTTPD `serve` + `newFixedLengthResponse(..., bytes.size)` | 4 MB | —— | 起点越界 → 416 |
| iOS | GCDWebServer `GCDWebServerStreamedResponse` + `resp.contentLength = span` | 256 KB | ≤8 MB 整包（图片需要 Content-Length）| `start > total` → 416；`start == size` 当最后一个字节 |
| OHOS | 无 | —— | —— | —— |

> iOS 的 `isRangeUnsatisfiable` **只把 `start > total` 当不可满足**，`start == total`（如 VLC 的 `bytes=<size>-` 探测）会退到最后一个字节。
> 原因是**非 faststart 的 MP4 的 moov box 在文件尾部**，严格回 416 会让播放器拿不到索引，
> 表现为拖动/跳转后直接 Ended。这是 iOS 端调出来的关键经验。

### 5.5 随机读实现对比（流式播放的底座）

| 维度 | Android（JSch）| iOS/macOS（NMSSH + 裸 libssh2）| OHOS（libssh2）|
|---|---|---|---|
| 句柄来源 | 专用 `ChannelSftp` | 每个句柄独立 `libssh2_sftp_init` | 复用会话的 `LIBSSH2_SFTP` |
| 随机读 | `channel.get(path, null, offset)`（skip 是**服务端偏移**）| `libssh2_sftp_seek64` + `libssh2_sftp_read` | `libssh2_sftp_seek64` + `libssh2_sftp_read` |
| 预读缓存 | ❌ 无 | ✅ 2 MB 预读 + `cacheStart` 命中 | ❌ 无 |
| 锁 | ❌ 无 | ✅ `ioLock` + `@try/@finally` | ✅ 会话级 `recursive_mutex` |
| EOF | 返回短读 | 返回空 NSData | 返回短读/空 |
| 已知风险 | 并发 Range 可能错乱 | 已加锁解决 | 每次 seek+read，无 PPL 竞争问题（会话锁串行）|

iOS 的 `KRSftpFileHandle` 关键实现：

```objc
static const long long kKRReadAheadUnit = 2 * 1024 * 1024;

[open.ioLock lock];
@try {
    // 1) 命中预读缓存
    if (open.cache.length > 0 && offset >= open.cacheStart &&
        offset + length <= open.cacheStart + open.cache.length) { ... }
    // 2) 未命中：读 max(length, 2MB)，seek64 后循环 read（处理 EAGAIN）
    libssh2_sftp_seek64(open.handle, (uint64_t)readStart);
    while (chunk.length < want) {
        ssize_t n = libssh2_sftp_read(open.handle, buf, ask);
        if (n > 0) append;
        else if (n == 0) break;                 // EOF
        else if (n == LIBSSH2_ERROR_EAGAIN) continue;
        else break;
    }
    open.cache = chunk; open.cacheStart = readStart;
    return subdata(0, length);
} @finally {
    [open.ioLock unlock];       // 所有分支都必须解锁，漏一个就永久死锁
}
```

Android 的 `SftpFileHandle` 关键实现（JSch 的坑）：

```kotlin
// ❌ 错误：get(src) 返回只能顺序读的流，对它 skip(offset) 会把绝对偏移当相对位移
//         多次读后位置错乱，播放器解析 MP4 直接失败
// ✅ 正确：get(src, monitor, skip)，JSch 会把 skip 作为 SFTP 读偏移下发（不传被跳过的字节）
val stream = channel.get(remotePath, null, offset)
```

> Android 的随机读**没有锁**。而 `LocalHttpProxyServer.serve` 跑在 NanoHTTPD 的请求线程上，
> 播放器可能并发发多个 Range → 同一个 `ChannelSftp` 并发 `get`，理论上会错乱。
> 目前实测（ExoPlayer）未触发，但这是与 iOS 的差距，后续应补锁/缓存。

### 5.6 播放器侧：VideoView 属性语义与 seek

`VideoView` 属性：`src / playControl(PLAY|PAUSE|PREPLAY|STOP) / seekTo(ms) / muted / rate / resizeMode`。
事件：`firstFrameDidDisplay / playStateDidChanged(state, ext) / playTimeDidChanged(cur, total)`，单位**毫秒**。

播放页（`SftpPlayerPage`）的几个关键设计：

1. **`playUrl` 用 `vif` 包住 `Video`**：URL 是异步就绪的，写在结构层的 `if` 首帧为 null，
   `Video` 不会被创建，之后也不会重建（黑屏）。
2. **`Video` 必须显式给尺寸**：只给外层容器尺寸时 `Video` 高 0，VLC 渲染视图也是 0（黑屏）。
3. **必须设 `playControl(PLAY)`**：不设时 VLC 只创建播放器不起播，也不会向代理发 Range 请求。
4. **`seekTarget` 与 `currentPosition` 分离**：
   - `currentPosition` 只用于**显示**，由进度回调刷新；
   - `seekTarget` 只用于**下发 seek**，仅在显式跳转时改。
   - 若把两者合一，进度回调每秒改写 `seekTo` → 每秒一次真实 seek（VLC 每次 flush+重缓冲）→ 卡顿。
5. **`isPlaying` 表示「用户意图」，不被播放器状态回调覆盖**：
   - 暂停时拖动/快进，原生为了保持 demuxer 活跃会临时起播，回包 `PLAYING`；
     若回调里把 `isPlaying=true`，按钮会变成「暂停」，与用户意图矛盾。
   - 修正：状态回调只在 `PLAY_END / ERROR` 时置 false。
6. **进度条拖动用 `pan` 手势**（不是 `touchDown/Move/Up`，实测 macOS 上 `touch*` 不回调）：
   拖动中只更新 `dragRatio` 预览，松手才 `applySeek`（避免一次拖动发几十次 seek）。
7. **续播**：进页读历史，`position > 10s` 弹续播框；每 5 秒落一次历史（注意 `cur` 单位是**毫秒**，
   写成 `cur % 5 == 0` 会每帧命中，必须 `cur/1000 % 5`）。
8. **自动下一集**：同目录视频排序成 episodes，`PLAY_END` → `markCompleted` + 3 秒倒计时。

### 5.7 这条链路上踩过的坑（按严重度）

1. **代理 Range 响应缺 `Content-Length`** → 播放器判为不可 seek → 改「带 Content-Length 的流式 206」。
2. **`KRSftpFileHandle` 预读缓存漏解锁** → 后续读取永久死锁（播放卡住）→ 用 `@try/@finally` 单一路径解锁。
3. **`_p_pendingSeekMs` 只写不读**（native 播放器）→ 未就绪时的 seek 被永久丢弃 → 状态变化/进度回调时补发。
4. **暂停态 seek 不取数据**（VLC）→ seek 前先 `play`，若用户意图是暂停则延时 `pause`。
5. **Android 用 `get(src)+skip` 随机读错位** → 改 `get(src, monitor, skip)`。
6. **OHOS `Connect` 字段名不匹配**（`username` vs `user`）→ 连接恒失败。
7. **OHOS `Download` 拿本地父目录去远端 mkdir** → 下载必失败；改 `EnsureLocalDir`。
8. **OHOS `Copy` 用 `/data/local/tmp` 中转 + 先下载到 `/dev/null`** → 沙盒不可写 + 双倍传输；
   改真正的远端→远端流式复制。
9. **iOS 代理 `totalSize` 为 0**（页面未传 size）→ 用 `openRead` 的 `fstat` size 回填。
10. **图片预览空白**：图片加载器不认没有 `Content-Length` 的流式响应 → 无 Range 小文件整包返回。

---

## 6. 平台实现层

### 6.1 Android（JSch + NanoHTTPD）

**依赖**：`com.jcraft:jsch:0.1.55`、`org.nanohttpd:nanohttpd:2.3.1`。
⚠️ 声明在**所有** `core-render-android/build.*.gradle.kts`（仓库按 Kotlin 版本命名多份构建脚本，
只改一份会 Unresolved，这是踩过的坑）。

**KRSftpModule.kt**：继承 `KuiklyRenderBaseModule`，重载两个 `call`：
- String 重载分派 19 个方法；
- Object 重载只拦截 `read`（解析 `Array<Any?>`）。

所有方法体走 `executeOnSubThread`（demo 是固定 2 线程池），回调 `mapOf(...)` / `payload.toMap()`。
`download/copy/batchTask` 会注入 `cacheDir`（`context.cacheDir`）。

**KRSftpClient.kt**（object，进程级）：

```kotlin
private val sessions = ConcurrentHashMap<String, SftpSession>()
private val fileHandles = ConcurrentHashMap<String, SftpFileHandle>()
private val sessionIdCounter = AtomicLong(0)     // "sftp-N"
private val fileHandleIdCounter = AtomicLong(0)  // "fh-N"
```

`connect`：

```kotlin
val jsch = JSch()
if (privateKey.isNotEmpty()) jsch.addIdentity(user, privateKey.toByteArray(), null, passphrase?.toByteArray())
val session = jsch.getSession(user, host, port)
if (password.isNotEmpty()) session.setPassword(password)
session.setConfig("StrictHostKeyChecking", "no")   // TODO：known_hosts 未实现
if (compression) session.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
session.connect(connectTimeoutMs)
session.setConfig("KeepAlive", "yes"); session.timeout = keepAliveIntervalSec * 1000
```

`SftpSession`：每个操作 `withChannel { }` 新开一个 `ChannelSftp` 并在 finally disconnect；
`lock` 只保护 channel 列表。`openRead` 例外，保留一条长连接 channel 供随机读。

文件操作要点：
- `list`：`channel.ls`，**忽略** `includeHidden/offset/limit`，`hasMore` 恒 false。
- `stat`：`stat` / `lstat`。
- `mkdir`：递归先 `stat` 探存在再建。
- `rm`：递归时**吞掉所有 `SftpException`**（权限错误也静默，是缺陷）。
- `copy`：SFTP 无服务端拷贝原语 → 下载到 `cacheDir/.sftp-copy-<nano>` 再上传，递归深度上限 64。
- `chmod`：`Integer.parseInt(mode, 8)`。
- `setMtime`：上层毫秒 → 秒级 int（JSch 0.1.55 只有两参版本）。
- `batchTask`：`DELETE/MOVE/COPY/DOWNLOAD`，逐项容错后汇总抛错；MOVE/COPY 先建目标目录。
- `cancelBatchTask`：同步执行无法中断，**显式 log no-op**（不静默）。

**LocalHttpProxyServer.kt**：NanoHTTPD 子类。

```kotlin
PORT_MIN = 18080; PORT_MAX = 18089
TTL_MS = 2h; MAX_CHUNK_BYTES = 4MB; SOCKET_READ_TIMEOUT_MS = 5s
```

- `startOrGet`：双重检查锁 + 端口 fallback。
- `serve`：校验 token（不存在 404 / 过期 410 并释放句柄）→ 懒 `openRead` → `parseRange` →
  `min(range.second, start + 4MB - 1)` → `KRSftpClient.read` → 206/200 + `Content-Length`/`Content-Range`/`Accept-Ranges`；
  起点越界回 416 + `Content-Range: bytes */total`。
- token：`SecureRandom` 16 字节 → 32 hex；`fileHandleId` 懒打开。

**存储**（`SharedPreferences`，**未加密**，Phase 1.2 计划 EncryptedSharedPreferences）：

| 文件 | key | 容量 | id |
|---|---|---|---|
| `sftp_connections` | `items` | 无上限 | UUID |
| `sftp_favorites` | `items` | 无上限 | UUID |
| `sftp_playback_history` | `items` | 2000 LRU | `"$connectionId|$remotePath".hashCode().toString(16)` |

> 单例 Module 的 storage **不能用 `by lazy` 缓存**：首次访问时 `context` 可能还没注入，
> 一旦缓存成 prefs=null 的实例，后续读写全部静默失效（`add` 返回 id 但 `list` 为空）。
> 每次调用重新 `new SftpFavoritesStorage(context)`。

**错误映射**（`SftpErrorFormatter.kt`）：`UnknownHost/ConnectException→1001`、`SocketTimeout→1002`、
`JSchException "Auth fail"→1003`、`"UnknownHostKey"→1004`、其他 JSchEx→1999、
`SftpException id=2→2001`、`id=3→2002`、`id=4→2999`、`IllegalStateException→3001`、其他→0。

**已知局限**：无 idle 断开、无 channel 池、随机读无锁、`shutdownAll` 从未调用、
`disconnect` 不清理 fileHandles、`rm` 递归吞异常、`move` 不预建目标目录、`overwrite` 忽略。

### 6.2 iOS / macOS（NMSSH + 裸 libssh2 + GCDWebServer）

**依赖**：
- `NMSSH` 用 **ridenui fork 2.7.2**（内置 libssh2 1.10.0）。官方 `~> 2.3.1` 内 libssh2 1.8.0 与
  OpenSSH 8.9 握手失败（TCP 通但 `Failure establishing SSH session`）。
- `GCDWebServer ~> 3.5.4`。
- iOS 与 macOS **共用同一份 ObjC**（`core-render-ios/Extension/Modules/`）。

**KRSftpModule.m**：每个方法都 `dispatch_async(KRSftpModuleSerialQueue(), ...)` 到**一条串行队列**，
这是线程安全的根本保证。`read` 回调 `@[meta, bytes]`。

**KRSftpSession.m**：
- `connect`：`NMSSHSession`，`session.timeout`；密码或 `authenticateByInMemoryPublicKey`；
  失败时把 `lastError` 拼进 `userInfo.detail` 抛出（否则握手失败与网络错误无法区分）。
- `list`：**归一化 NMSFTP 给目录名追加的 `/`**，否则 UI 显示 `Documents/` 且按名匹配全失效。
- `stat`：`infoForFileAtPath` 对目录会失败（内部用 `FXF_READ` 打开）→ 回退到父目录 listing 里找。
- `download`：落到 `Caches/KRSftpDownloads`。
- `mkdir`：NMSFTP 无递归 → 逐段建。
- `copy`：下载到本地临时文件再上传（避免整文件进内存）。
- `chmod/chown/setMtime`：**通过 SSH channel 执行 shell 命令**（NMSSH 不直接支持），
  路径用单引号包裹。
- 存储：`NSUserDefaults`。连接用 `NSKeyedArchiver` 经典归档
  （用实例 API 规避 iOS 12 废弃告警，因为 Pod 开了 `GCC_TREAT_WARNINGS_AS_ERRORS=YES`）；
  收藏/历史用 JSON `NSData`。key：`sftp_connections_items` / `sftp_favorites_items` / `sftp_playback_history_items`。

**已确认的跨模块 Bug（读代码即可复现）**：`KRSftpConnectionModule` 删连接时的级联清理
`clearFavoritesByConnectionId:` / `clearHistoryByConnectionId:` 读的是收藏/历史的 key，
却用了**连接模块自己的** `KRUnarchiveArray`（keyed archive）去解析 ——
而这两个模块写的是 JSON。`KRUnarchiveArray` 遇到 JSON 返回 nil → 过滤结果为空 →
再把空数组**以 keyed archive 格式写回**，等于把 JSON 数据覆盖坏。
即「删除连接会清空/损坏收藏与历史」。修法：级联路径改用与写入方一致的 `NSJSONSerialization`。
（对照：Android/OHOS 的级联是直接调各自的 storage 类，格式一致，无此问题。）

**错误码不一致（iOS）**：`KRSftpModule` 里 `mkdir/rm/rename/move/chmod/chown/setMtime`
失败时构造的是 domain = `"KuiklySftp"` 的 `NSError`，而 `SftpErrorFormatter.formatNSError:` 只认
NMSSH domain → 最终 `code = 0`（作者本意是 2001）。另外收藏/历史模块部分失败路径直接回
`e.reason` 裸字符串，不是 `SftpError` JSON。

**KRSftpFileHandle.m**：核心是 §5.5 的 `libssh2` 直连 + 2MB 预读 + `ioLock`。
- 用 `libssh2_sftp_open_ex` 而非宏 `libssh2_sftp_open`（宏会把 `strlen()` 的 `size_t` 窄化成
  `unsigned int`，iOS Pod 开了 `-Wshorten-64-to-32` 会编译失败）。
- `fstat` 取 size 供 Content-Length/Range 计算。
- `close` 释放 `handle` 与 `sftp`（每个句柄独立 `libssh2_sftp_init`）。

**KRLocalHttpProxy.m**：
- `GCDWebServer`，端口 18080-18089。
- 用 `addDefaultHandler` 给未匹配请求回 404（GCDWebServer 默认 501）。
- `tokenForId` 过期即删并续期。
- `responseForRequest`：懒打开句柄 → 416 判断 → 有 Range 走流式 206（256KB/片，`contentLength=span`）
  → 无 Range 且 ≤8MB 整包（图片需要 Content-Length）→ 否则流式 200。
- `+mimeForPath:` 内嵌扩展名表。
- Kotlin/Native 桥：`core` 不能依赖 renderer，故通过 `NSClassFromString + performSelector`
  调 `startOrGetPortNumber` / `registerTokenWithJson:` / `unregisterTokenWithJson:` / `stopProxy`。

**KRLocalMediaProxyModule.m**：`KRBaseModule` 子类，把上述方法暴露给 Module 体系。

**iOS 特有坑**：
- Pod 警告即错误：`libssh2_sftp_open` 窄化、废弃 API、未使用常量。
- 首页 `ContentView.swift` 的 `.ignoresSafeArea()` 会压到状态栏。
- iOS 宿主缺 `KBridgeModule.toast:` → DEBUG 下 `NSAssert` 崩溃（保存连接即崩），已补。
- 视频实现从 **WMPlayer 换成系统 AVPlayer + AVPlayerLayer**：
  WMPlayer 自带一套控件无法关闭、`+IsiPhoneX` 访问 `delegate.window` 崩溃、
  `resetWMPlayer` 不摘周期观察者导致 SIGFPE。
  实现 `KRVideoViewProtocol` 的类必须显式 `@synthesize krv_delegate;`。
- 全屏：`VideoView.setFullscreen(Boolean)` → 原生旋转；页面用 `vif` 隐藏导航栏。
- macOS 用 VLCKit，seek/暂停语义与 iOS 不同，需另行验证。

### 6.3 HarmonyOS（libssh2 + mbedTLS）

**构建接线**（`core-render-ohos/src/main/cpp/CMakeLists.txt`）：
- `SOURCE_SET` 加入 8 个 sftp 源文件。
- vendored 静态库：`thirdparty/libssh2-ohos/arm64-v8a/lib/{libssh2,libmbedtls,libmbedx509,libmbedcrypto}.a`。
- 用 `CMAKE_CURRENT_SOURCE_DIR` **绝对路径**（相对路径在 DevEco 独立构建目录下 `EXISTS` 失败，
  配置期只 warning，链接期才炸）。
- 重建方式：DevEco 自带 `ohos.toolchain.cmake` 交叉编译 mbedTLS，再 `-DCRYPTO_BACKEND=mbedTLS` 编 libssh2。

**注册**：`ModulesRegisterEntry.h` 注册 4 个模块（无 Proxy）。

**KRSftpInternal.h**：

```cpp
struct SessionHandle {
    LIBSSH2_SESSION *session; LIBSSH2_SFTP *sftp; int sock;
    std::string home;
    std::recursive_mutex io;   // 递归：批量操作会重入 Rename/Rm/Copy
    bool broken = false;
};
using SessionPtr = std::shared_ptr<SessionHandle>;
struct OpenFileHandle { LIBSSH2_SFTP_HANDLE *handle; SessionPtr session; };
```

`OpenFileHandle` 持 `SessionPtr`，避免「会话已断开但读句柄仍在读」的 use-after-free。
`MethodGuard::Acquire` 统一取 `io` 锁并拒绝 broken 会话。

**KRSftpSession.cpp**：
- `TcpConnect`：非阻塞 connect + `select` 超时 + `SO_ERROR` + `TCP_NODELAY`（DNS 本身无超时）。
- `connect`：阻塞模式 + `libssh2_session_set_timeout(readTimeoutMs)`；密码或
  `libssh2_userauth_publickey_fromfile`；`libssh2_sftp_realpath` 取 home。
- **无 keepalive**。
- `ThrowSftp` 会把会话标记 `broken`（任何 SFTP 错误都这样，比较激进；递归 helper 用普通
  `runtime_error` 避免污染会话）。
- `List` 返回全部，过滤/分页在 Module 层做（`includeHidden/offset/limit/hasMore` 都实现了，
  与 Android 不同）。
- `Download/Upload` 续传用显式 `seek64`，**不能用 `FXF_APPEND`**（APPEND 会忽略 seek 导致错位）。
- `Copy` 真正的远端→远端流式复制 + 符号链接重建。
- `BatchTask` 契约与 Android 对齐。
- `CancelBatchTask` 幂等空操作（**绝不能 Disconnect**，taskId 不是 sessionId）。

**KRSftpFileHandle.cpp**：`open(FXF_READ)` + 每次 `seek64`+`read`，无缓存；
返回 `[{"ok":true}, ByteArray]`。

**KRSftpModule.cpp**：每个方法 **detach 一个线程**，callback 回包。

**SftpJsonStore.cpp**：JSON 数组文件，temp+rename 原子写，**无锁**（并发读改写会丢更新）。
容量 2000 LRU（history）。连接删除级联清收藏/历史。

**KRLocalHttpProxy.cpp**：**桩**。`Start()` 只设 `port_=18080`，MHD 启动代码被注释；
`registerToken` 只做 token 注册（`rand()` 未播种）；且该文件**未进 CMake SOURCE_SET**，
也没有 `KRLocalMediaProxyModule`。→ **OHOS 不支持播放 SFTP 视频**。

**错误映射**（`SftpErrorFormatter.cpp`）：对 `what()` 子串匹配
`"not implemented"→9999` / `"NoSuch|no such"→2003` / `"Permission|permission"→2001` /
`"connect"→1001` / `"auth"→1003`，否则 0。不转义 JSON，msg 含引号会产出非法 JSON（潜在）。

### 6.4 Web / 小程序（架构受限）

浏览器无原始 socket，无法建 SSH/SFTP。设计上**不启本地代理**。
唯一路径是**后端网关**（§5.6）：由服务端代持 SFTP，暴露 HTTP Range 接口，前端当普通 HTTP 视频播。
JS 产物本身可构建（`:demo:packLocalJsBundleDebug` + `:h5App:jsBrowserDevelopmentWebpack`），
但 SFTP 能力在浏览器内不可用。这是**架构限制，不是实现缺陷**。

---

## 7. UI 层

### 7.1 页面清单与路由图

```
SftpHomePage（连接 / 收藏 / 历史 Tab）
  ├─[+]→ SftpConnectEditPage（保存后返回）
  └─[连接]→ SftpBrowserPage
              ├─[目录]→ 自身（换 currentPath 重新 list）
              ├─[视频/音频]→ SftpPlayerPage
              └─[其他]→ SftpViewerDispatcherPage
                          ├─ TEXT     → SftpTextViewer
                          ├─ MARKDOWN → SftpMarkdownViewer
                          ├─ HTML     → SftpHtmlViewer
                          ├─ IMAGE    → SftpImageViewer（经本地代理）
                          ├─ PDF      → SftpPdfViewer
                          └─ AUDIO    → SftpAudioViewer（经本地代理）
（另有 SftpFavoritesPage / SftpHistoryPage / SftpFilePropsPage / SftpBatchProgressDialog）
```

### 7.2 SftpBasePager

```kotlin
override fun createExternalModules(): Map<String, Module>? = hashMapOf(
    BridgeModule.MODULE_NAME to BridgeModule(),        // 宿主能力（toast）
    SftpModule.MODULE_NAME to SftpModule(),            // 绑定 Page
    SftpFavoritesModule.MODULE_NAME to SftpFavoritesModule(),
    SftpPlaybackHistoryModule.MODULE_NAME to SftpPlaybackHistoryModule(),
    SftpConnectionModule.MODULE_NAME to SftpConnectionModule(),
    SftpMediaProxyModule.MODULE_NAME to SftpMediaProxyModule()
)
```

提供 `sftpModule()` / `sftpFavoritesModule()` 等 accessor；`themeDidChanged` 同步夜间模式到 `SftpColorTokens`。

### 7.3 响应式铁律（Kuikly 页面通用）

这几条来自真实故障，改任何 Kuikly 页面都可能遇到：

1. **响应式依赖只在 `attr {}` / `vif/velseif/velse` / `vfor` 的条件 lambda 内收集**。
   写在 `body()` 结构层的 `if/when`/局部变量只在首帧求值一次，之后状态变化**不会重建分支**。
   症状：页面永远停在「加载中」、Tab 切换不刷新、切目录列表不更新。
2. **状态字段必须是 `observable`**；列表用 `observableList` + `vfor`（按 diff 增删）。
   普通 `var` 改了不触发重渲染。列表即使非空→非空变化，也只有在 `vfor + observableList` 下才重建。
3. **原生组件必须显式给尺寸**（布局引擎不做原生控件测量）：
   `Input` 不写 `height` → 高 0、光标不可见、点不聚焦；`Image` 不写 `size` → 0×0 不可见。
4. **顶部安全区**：Android 沉浸式/刘海屏下页面自绘导航栏会被状态栏遮挡且**吞掉点击**
   （症状：「+ 新建」点不动）→ 根容器统一 `paddingTop(pagerData.statusBarHeight)`。
5. **`Scroller` 包列表**：列表直接铺在普通 View 上没有滚动能力，超过一屏就够不到。
6. **单位用 `pagerData.pageViewWidth/Height`**，不要用设备像素。

### 7.4 各页面实现要点

- **SftpHomePage**：连接/收藏/历史三 Tab。用 `vif/velseif/velse` 表达 loading/error/empty/list 四态。
  `pageDidAppear` 里再次刷新（首页是导航栈根，返回时 `created` 不会再跑）。
- **SftpBrowserPage**：`created` 里 connect→list；点目录改 `currentPath` 重新 list；
  点文件按 `MimeExtMap` 分发到播放页或预览分发页；返回时 `disconnect`。
- **SftpPlayerPage**：§5.6。
- **SftpViewerDispatcherPage**：扩展名/MIME 决定 viewer；文本类先 `openRead` 读 8KB 检测编码；
  图片/音频由页面**异步申请代理 token**，再通过 provider 传给组件（侧效应放页面层，组件只渲染）。
- **SftpConnectEditPage**：表单 host/port/user/password；保存调 `SftpConnectionModule.add`；
  toast 失败不影响返回（try/catch 包裹，否则用户以为保存失败）。
- **SftpFilePropsPage**：`stat` 展示属性；`permToOctal` 把 `rwxr-xr-x` 转 `755`；chmod/chown/setMtime 编辑。
- **SftpFavoritesPage / SftpHistoryPage**：列表 + 排序/搜索 + stale 检测 + 进度条。
- **SftpBatchProgressDialog**：展示批量进度，取消调 `cancelBatchTask`。

### 7.5 预览子系统当前状态

| 类型 | 实现程度 |
|---|---|
| 文本 | ✅ 已拉头部 8KB + 编码检测后渲染（Phase 0.4 只渲染 head）|
| 图片 | ✅ 经本地代理 `Image` 渲染（页面异步拿 URL）|
| 音频 | ✅ 复用 `Video` 组件 + 本地代理 URL |
| Markdown | ⚠️ 占位（待接 markdown 组件）|
| HTML | ⚠️ 占位（待接 WebView）|
| PDF | ⚠️ 占位（待接原生 PDF 渲染）|

> 反例记录：`SftpImageViewer` 曾在 `body()` 里**同步**调代理拿 token，token 恒为空 → 图片预览一直是坏的。
> 修正：由 `SftpViewerDispatcherPage` 异步申请后经 provider 传入。这是「侧效应放页面层」的典型。

### 7.6 主题 / i18n / 无障碍

- `SftpColorTokens`：7 个 token 日夜双色，由 `SftpBasePager` 同步。
- `I18n`：所有文案走 `I18n.t(key)`。
- `SftpAccessibility`：按钮 label + 列表项朗读模板。

---

## 8. 测试与验证

### 8.1 集成自测页设计（`SftpIntegrationTestPage`）

- 把 §7.1/§7.2 全部方法串成一个**步骤数组**，串行执行，每步断言 PASS/FAIL 并写日志（tag `SftpTest`）。
- 服务器参数通过 `pageData` 注入（host/port/user/password/remoteHome），**凭据不进源码**。
- 结果形如：`STEP n/N | name` / `PASS | name | detail` / `FAIL | name | detail`，
  结尾 `SUITE END total=N pass=N fail=N` + 每条 `FAILURE ->`。
- 覆盖：连接、浏览、stat、流式读（含 offset/EOF/越界）、上传下载、mkdir/rm/rename/move/copy、
  chmod/chown/setMtime、batchTask、收藏/历史/连接 CRUD、本地代理端口与 token。

**字节级校验**（证明传输真的没坏）：测试素材 `sftp_kuikly_media.mp4`（95627 字节）：

```kotlin
MEDIA_SIZE = 95627L
MEDIA_SUM  = 11393384L                       // sum(byte) % 1000000007
MEDIA_HEAD16 = "000000206674797069736f6d00000200"   // ftyp box
MEDIA_MID16  = "bf83b361bd4b46fbbca64bc57df7c2ef"   // offset 50000
MEDIA_TAIL16 = "a934fb2481a7d1640914be011881b470"   // 末尾
```

读整个文件按 64KB 分块累计字节数与校验和，和期望比对；下载→重传→再读也做同样校验。
代理 token 注册后打印 `PROXY_URL`，可用 `curl` 从 HTTP 层独立验证 Range（206/Content-Range/后缀 Range/越界）。

### 8.2 六端构建 / 验证矩阵

| 端 | 构建命令 | 验证 |
|----|---------|------|
| macOS | `xcodebuild -workspace macApp.xcworkspace -scheme macApp ...` | 界面操控：播放/拖动 seek/暂停恢复 |
| iOS | `xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator ...` | 模拟器 74/74 + AVPlayer 播放 |
| Android | `./gradlew :androidApp:assembleDebug`（JDK 17）| 模拟器 74/74 ×10 轮 + ExoPlayer 播放 |
| HarmonyOS | `./2.0_ohos_demo_build.sh` → DevEco 构建 | 编译通过；运行时未验证（无设备）|
| Web | `:demo:packLocalJsBundleDebug` + `:h5App:jsBrowserDevelopmentWebpack` | 架构受限 |
| MiniApp | 同上 + `:miniApp:jsMiniAppDevelopmentWebpack` | 架构受限 |

### 8.3 测试服务器（内网低敏，已授权入库）

| 项 | 值 |
|----|----|
| host | `192.168.2.2` |
| port | `22` |
| user / password | `zhaojian` / `zhaojian` |
| remoteHome | `/home/zhaojian` |

跑全量自测：临时把 `iosApp/iosApp/ContentView.swift`（或 `macApp`）指向 `SftpIntegrationTestPage` 并注入参数，
构建运行后从日志 grep `[KLog][SftpTest]`；测完改回 `SftpHomePage`。
自动化运行记得 `KUIKLY_SUPPRESS_ERROR_ALERT=1`（DEBUG 下 `KRLogModule.logError` 会弹模态框）。

### 8.4 诊断日志

macOS：`~/Library/Containers/com.tencent.kuiklycore.macApp/.../Logs/KuiklyMacApp/diagnostics.log`（JSONL）。
关键 tag：`diag.*` / `page.life` / `page.load` / `uilayout`（视图树 dump）/ `video`（VLC 状态）/
`kuikly`（`[module]` 调度、`[module-cb]` 回包、`[sftp.proxy]` 请求）。
`uilayout/viewTree` 是排查「原生控件 0 尺寸」的利器。

---

## 9. 跨端一致性硬规则与踩坑总表

### 9.1 三条硬规则（可当 review checklist）

1. **桩实现必须显式失败，绝不能伪报成功。**
   反例（修复前 OHOS）：`Connect` 不连接返回假 sessionId、`Upload/Download` 直接 `return 1.0f`、
   `Copy` 返回 success。用户看到「上传 100%」而远端无文件 —— **静默数据丢失**。
   现所有未实现统一抛异常 → 错误码 `9999`。
2. **错误码跨端同一套语义**（`SftpErrorCode`）。各端 `SftpErrorFormatter` 负责映射，UI 只认 code。
3. **侧效应放页面层，组件只渲染。**
   反例：`SftpImageViewer` 在 `body()` 同步调代理拿 token 恒为空。现由页面异步申请、provider 传入。

### 9.2 框架级坑

| # | 坑 | 症状 | 修法 |
|---|---|---|---|
| 1 | 结构层 `if/when` 不重建 | 永远加载中 / Tab 不刷新 / 切目录不更新 | 用 `vif/velseif/velse` |
| 2 | 普通 `var` 不可观察 | 续播弹窗/倒计时/抽屉永不出现 | `observable` / `observableList` |
| 3 | 原生控件无尺寸 | Input 点不聚焦、Image 不可见 | 显式 `height()/size()` |
| 4 | 状态栏吞点击 | 「+ 新建」点不动 | 根容器 `paddingTop(statusBarHeight)` |
| 5 | 列表不在 Scroller | 超过一屏够不到 | 包 `Scroller` + `vfor` |
| 6 | 裸 JSONObject/JSONArray 过不了桥 | 列表恒空、字段全空 | `JSONObject(...).toMap()` |
| 7 | 新增原生文件不重跑 pod install | 模块名解析失败，DEBUG `NSAssert` abort | 重跑 `pod install`（UTF-8 locale）|
| 8 | 漏改某份 Kotlin 版构建脚本 | 依赖 Unresolved | 改所有 `build.*.gradle.kts` |
| 9 | `I18n` 缺 key | 界面出现 `sftp.xxx.yyy` | 补 `I18n.kt` |
| 10 | DEBUG logError 弹模态框 | 自动化被打断 | `KUIKLY_SUPPRESS_ERROR_ALERT=1` |
| 11 | 拖动用 touch* 不回调 | 进度条拖不动 | 用 `event { pan { } }` |

### 9.3 播放器坑（§5.7 的浓缩）

`seekTo` 不绑进度（独立 seekTarget）｜`isPlaying` 表示用户意图｜必须显式尺寸｜必须设 `playControl(PLAY)`｜
`_p_pendingSeekMs` 要补发｜Range 响应必须有 `Content-Length`｜暂停态 seek 先 play 再 pause｜
`playTimeDidChanged` 单位是毫秒｜退出播放页停表（NSTimer 保留环）｜`@synthesize krv_delegate`。

### 9.4 各端行为差异总表（做兼容时必看）

| 能力 | Android | iOS/macOS | OHOS |
|---|---|---|---|
| `list` 的 includeHidden/offset/limit/hasMore | ❌ 忽略，hasMore 恒 false | ❌ 忽略 | ✅ 实现 |
| `overwrite`（SKIP/FAIL/RENAME）| ❌ 一律覆盖 | ❌ 一律覆盖 | ❌ 一律覆盖 |
| 错误码：文件不存在 | **2001** | 2003 | 2003 |
| 错误码：权限 | **2002** | 2001 | 2001 |
| 断点续传 offset | ❌ 忽略 | ❌ 忽略 | ✅ seek64 |
| 本地 HTTP 代理 | ✅ NanoHTTPD | ✅ GCDWebServer | ❌ 桩 |
| 视频播放 | ✅ ExoPlayer | ✅ AVPlayer/VLCKit | ❌ |
| `copy` 实现 | 下载→上传（本地中转）| 下载→上传（本地中转）| 远端→远端流式 |
| 存储 | SharedPreferences（明文）| NSUserDefaults（明文）| JSON 文件（明文）|
| 存储并发保护 | 固定线程池，无事务锁 | 每模块串行队列 | 无锁 |
| 删连接级联清收藏/历史 | ✅ 格式一致 | ❌ **格式不一致，会损坏数据**（见 §6.2）| ✅ 格式一致 |
| 布尔类操作失败错误码 | 3001（IllegalState）| 部分为 **0**（formatter 不认 `KuiklySftp` domain）| 0（无匹配子串）|
| 代理 token 长度 | 32 hex | 64 hex | 无代理 |

> 特别提醒：**错误码 Android 与 common 枚举是反的**。UI 若按 code 分支显示文案，Android 上会把
> 「文件不存在」显示成「权限不足」。改 UI 或加端时以 `SftpErrorCode` 为准并统一各端 formatter。

---

## 10. 扩展指南

### 10.1 新增一个 SFTP 能力（四步）

1. **commonMain 声明**：`core/.../module/sftp/XxxModule.kt`
   ```kotlin
   fun doSomething(a: String, callback: (result: String?, error: SftpError?) -> Unit) {
       val p = JSONObject(); p.put("a", a)
       asyncToNativeMethod(METHOD_DO, p) { data ->
           val err = data?.str("error")
           if (!err.isNullOrEmpty()) callback(null, SftpError.fromJson(err))
           else callback(data?.str("result"), null)
       }
   }
   ```
2. **常量**：`ModuleConst.kt` 加模块名（若新模块）；方法名常量。
3. **各端同名实现**：Android `KRSftpModule.call` 分派、iOS 加 `- (void)doSomething:(NSDictionary *)args`、
   OHOS `CallMethod` 分派。未实现的端**必须显式抛 9999**。
4. **页面注册**：`SftpBasePager.createExternalModules()` + `acquireModule`。

回归清单：三端编译；跑 `SftpIntegrationTestPage` 加一条断言；确认错误码与 common 一致；
确认线程安全（是否共享 session/channel）；确认 UI 状态是 observable。

### 10.2 新增一个端（如补齐 OHOS 媒体链路）

1. 协议栈：OHOS 已有 libssh2，随机读已实现。
2. 本地代理：实现 `KRLocalHttpProxy`（libmicrohttpd 或自写 HTTP server）+ 注册
   `KRLocalMediaProxyModule`，并**把它加进 CMake `SOURCE_SET`**。
3. 视频组件：OHOS 渲染器目前没有视频组件，需要新增 `KRVideoView` 等价实现或接入系统 AVPlayer。
4. 跑通后再更新 §1.3 矩阵与 AGENTS.md §13。

### 10.3 改动时的通用检查

- 改 `core/`：绝不能 import `core-render-*`。
- 改页面：条件用 `vif`、状态用 `observable`、原生组件给尺寸。
- 改原生：注意线程安全（session/channel 不并发）、错误必须映射到 `SftpErrorCode`、
  桩实现显式失败、新增源文件同步 pod install / CMake。
- 改播放：Range 响应带 `Content-Length`、seek 与进度分离、离开页释放 token。

---

## 11. 文件索引

| 主题 | 路径 |
|---|---|
| 能力声明（主）| `core/src/commonMain/.../module/sftp/SftpModule.kt` |
| 数据模型 | `core/src/commonMain/.../module/sftp/SftpModel.kt` |
| 错误码 | `core/src/commonMain/.../module/sftp/SftpErrorCode.kt` |
| 连接/收藏/历史/代理 | `core/src/commonMain/.../module/sftp/Sftp{Connection,Favorites,PlaybackHistory,MediaProxy}Module.kt` |
| MIME / 编码 / URL / i18n | `core/src/commonMain/.../module/sftp/{MimeExtMap,EncodingDetector,SftpMediaUrlBuilder,I18n}.kt` |
| 页面 | `demo/src/commonMain/.../pages/sftp/`（含 `viewer/` 与 `theme/`）|
| 集成自测 | `demo/src/commonMain/.../pages/sftp/SftpIntegrationTestPage.kt` |
| Android 桥 | `core-render-android/.../expand/module/KRSftpModule.kt` |
| Android 引擎 | `core-render-android/.../expand/module/KRSftpClient.kt` |
| Android 代理 | `core-render-android/.../expand/module/LocalHttpProxyServer.kt`、`KRLocalMediaProxyModule.kt` |
| Android 存储 | `core-render-android/.../expand/module/Sftp{Connection,Favorites,PlaybackHistory}Storage.kt` |
| Android 注册 | `core-render-android/.../expand/KuiklyRenderViewBaseDelegator.kt` |
| iOS/macOS 桥 | `core-render-ios/Extension/Modules/KRSftpModule.m` |
| iOS/macOS 引擎 | `core-render-ios/Extension/Modules/KRSftpSession.m` |
| iOS/macOS 随机读 | `core-render-ios/Extension/Modules/KRSftpFileHandle.m` |
| iOS/macOS 代理 | `core-render-ios/Extension/Modules/KRLocalHttpProxy.m`、`KRLocalMediaProxyModule.m` |
| iOS/macOS Pod | `iosApp/Podfile`、`macApp/Podfile`、`macApp/NMSSH.podspec` |
| OHOS 引擎 | `core-render-ohos/.../modules/sftp/KRSftpSession.cpp`、`KRSftpFileHandle.cpp` |
| OHOS 桥 | `core-render-ohos/.../modules/sftp/KRSftpModule.cpp` |
| OHOS 存储 | `core-render-ohos/.../modules/sftp/SftpJsonStore.cpp` |
| OHOS 注册/构建 | `core-render-ohos/.../ModulesRegisterEntry.h`、`core-render-ohos/src/main/cpp/CMakeLists.txt` |
| 设计文档 | `docs/SFTP-Client.md`（§23 跨平台架构）|
| 踩坑与状态 | `AGENTS.md` §13 |

---

> 这份文档由代码实现反向整理而成，随实现演进需要同步更新。
> 若发现与代码不一致，以代码为准，并回来修正本文。
