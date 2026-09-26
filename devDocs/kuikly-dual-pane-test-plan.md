# Kuikly 跨端项目：双栏文件管理器（Files）—— 测试用例与执行规程

> **目的**：在 Kuikly 跨端项目的 SFTP 客户端中**新增双栏文件管理器**（本地 + 远端，双向浏览/操作/传输），并按此文件里的测试用例**开发完后逐条跑一遍**作为验收门禁。
>
> **测试基线：以 Electron 那个版本为准**（即 `electron/test/smoke.mjs` 在打包版 `dist/mac/Kuikly SFTP.app` 下 10/10 通过的形态）。双栏是**桌面端**能力，**主自动化验证在 Electron 宿主里跑**；H5/Web harness 只服务 SFTP 单栏链路。
>
> 关联：
> - Janus 原文 `/Users/zhaojian/bin/macmini/janus`（GitHub `https://github.com/AdrianAndroid/janus`，分支 `zhaojian`，提交 `e3ba0e1 添加双栏`）—— 参考其双栏 + 传输编排 + FileEditor 的 UX 形态与边界
> - 传输引擎：`packages/file-transfer`（`devDocs/kuikly-file-transfer-module.md`）—— 双栏"触发传输"走它
> - 既有测试规程：`devDocs/sftp-test-plan.md`（L0–L5 分层 + 一键 `bash scripts/run-all-tests.sh`）—— 本文件的「双栏用例」按 L1/L2/L3 加入既有"自动化执行"链路
> - 既有渲染层：`demo/src/commonMain/kotlin/com/tencent/kuikly/demo/pages/sftp/SftpBrowserPage.kt`（单栏浏览）—— 双栏是它的**超集**，不是替换

---

## 0. 目标与非目标

### 0.1 目标
- 在 Kuikly 跨端项目的 SFTP 客户端中提供**双栏文件管理器**：
  - 左/右两栏：分别浏览**本地 FS**与**远端 SFTP**
  - 跨栏拖拽 / 双击进入 / 按钮 → 走 **`packages/file-transfer` 引擎**（`TransferRequest`）
  - 冲突解决：size 不一致时由 `ConflictResolver` 决策（overwrite/rename/skip/resume/cancel）
  - 轻量远程编辑：≤2MB 文本文件就地编辑并保存（写回远端）；>2MB 提示用本地编辑器
  - 与现有 SFTP 单栏（`SftpBrowserPage`）共存（前者用于"全屏浏览"；双栏用于"对照管理/批量传输"）

### 0.2 非目标
- ❌ 不替代 `SftpBrowserPage`（单栏浏览入口保留）
- ❌ 不在双栏模块内实现本地 FS 业务导航（沿用宿主提供的 `localfs:*` IPC；与 §3.2 边界一致）
- ❌ 不复制传输引擎实现（双栏只**调用** `packages/file-transfer`，不重写）
- ❌ 不实现 >2MB 文件编辑（提示用户用本地编辑器；Janus 同样限制）
- ❌ 不做"双栏文件实时同步"（Janus 显式不做；本计划同样不做）

---

## 1. Janus 双栏实现：架构摘要（参考依据）

> 详细源码以 `/Users/zhaojian/bin/macmini/janus` 分支 `zhaojian`（提交 `e3ba0e1 添加双栏`）为准。

### 1.1 关键文件
| 角色 | 文件 | 作用 |
|---|---|---|
| 双栏 UI | `src/renderer/src/FilesPanel.tsx` | 双栏布局（Local / Remote）、导航、选择、工具栏、命令面板入口 |
| 队列 UI | `src/renderer/src/components/TransferQueue.tsx` | 展示传输进度/状态/冲突 |
| 文件编辑 | `src/renderer/src/FileEditor.tsx`（或 `components/`）| ≤2MB 文本文件编辑并保存回远端 |
| 状态 | `src/renderer/src/store.ts` | `transfers` / `conflicts` / `rememberedActions`、全局事件监听 |
| 主进程引擎 | `src/main/transfer-manager.ts` | 队列 / 断点续传 / 冲突 / 重试（详见 `kuikly-file-transfer-module.md` §1）|
| 主进程 IPC | `src/main/ipc.ts` | 路由 `localfs:*` / `transfer:*` 到 handler |
| 桥接 | `src/preload/index.ts` | 暴露 `window.localFs.*` / `window.transfer.*` |
| 本地 FS | `src/main/local-fs.ts` | `home/list/mkdir/rename/remove/stat/dir-size` |

### 1.2 关键行为（双栏层面）
- **两栏独立浏览** — 每栏各自维护 cwd + 选中集 + 排序 + 过滤；互不耦合。
- **跨栏操作 = 传输**：
  - 拖拽本地文件 → 远端 → 调 `transfer.start({direction:'upload', localPath, remotePath, isDir})`
  - 拖拽远端文件 → 本地 → 调 `transfer.start({direction:'download', localPath:remotePath, remotePath:localPath?})`（按 Janus 实际参数方向）
  - 双击/Enter → 进入目录；Backspace → 上级
  - 选中后工具栏按钮 / 右键 → 触发 mkdir / rename / remove / 复制（→ 走 transfer）/ 打开 FileEditor（远端文件）
- **冲突 UX**：
  - `transfer:conflict` 事件携带 size/mtime → 弹窗询问
  - 记住选择按 direction 持久化（`rememberedActions`），下次同向同路径自动应用
  - 同尺寸（已传完）视为"已传输"跳过
- **传输状态反馈**：
  - `transfer:progress` 推进
  - 终态（done/error/canceled）显示
  - 队列 + 通知（`F3` 任务完成系统通知）
- **FileEditor**（≤2MB）：打开 → 拉取内容 → 编辑 → 保存 → 走 `transfer.start(direction:'upload', localPath:/tmp/..., remotePath:originalRemotePath)` 写回
- **持久化**：传输历史 / 记忆动作 / 会话恢复（断线后 `transfer:resume` 续传）

### 1.3 与 `packages/file-transfer` 的关系
- Janus 引擎在 `src/main/transfer-manager.ts`（TypeScript）；KuiklyUI 移植到 `packages/file-transfer`（同语义，TS→Kotlin/JS）
- 双栏**不重写**引擎，只**调用**引擎 API（`start/cancel/resume/resolveConflict/list`）
- 双栏依赖注入的 `TransferRequest` / 冲突处理 与 引擎契约一致（见 `kuikly-file-transfer-module.md` §3.1）

---

## 2. KuiklyUI 双栏模块：隔离设计（与 `file-transfer` 同原则）

### 2.1 隔离原则（与文件传输模块一致）
- **不**依赖 KMP 渲染层 / Compose DSL 之外的业务
- **不**直接做网络 IO / SSH（委托 `SftpModule`，复用现有 SFTP 能力；详见 `SftpBrowserPage`）
- **不**重写传输引擎（依赖 `packages/file-transfer` 提供的能力）
- **不**做"双栏文件实时双向同步"
- **核心逻辑**与 **UI** 分离：核心 = 纯 Kotlin（无 UI 依赖）→ 在 `commonTest` 可单测；UI = Kuikly Compose 组件，仅消费核心

### 2.2 包与目录（推荐）
- **主自动化验证目标 = Electron 宿主**（与 `electron/test/smoke.mjs` 同形），不是 Web/H5 harness
```
KuiklyUI/
├─ core/  (KMP 公共层)
│  └─ file-manager/  (新模块)
│     ├─ src/commonMain/kotlin/.../filemanager/
│     │  ├─ FileManagerState.kt        不可变状态 + reducer
│     │  ├─ FileManagerAction.kt        动作 + 传输请求构造
│     │  ├─ PathPolicy.kt               路径校验/越界/沙箱
│     │  ├─ TransferIntentBuilder.kt    选区→TransferRequest（调 file-transfer）
│     │  └─ FileManagerModule.kt        对外门面（提供 ListLocal / ListRemote / Apply / OpenEditor…）
│     ├─ src/commonTest/kotlin/.../filemanager/   单元测试（Kotlin/JS 跑）
│     │  ├─ StateTest.kt
│     │  ├─ TransferIntentBuilderTest.kt
│     │  └─ PathPolicyTest.kt
│     └─ ...
├─ packages/file-transfer/  (已存在，引擎)
├─ demo/src/commonMain/kotlin/.../pages/sftp/
│  └─ FilesDualPanePage.kt            渲染层：消费 FileManagerModule
├─ devDocs/kuikly-dual-pane-test-plan.md (本文)
├─ electron/  (M2 起接入 file-manager 到 Electron 桌面壳)
│  └─ main.js / preload.js 桥接"双栏 → 引擎"
└─ ...
```

### 2.3 对外 API（核心，框架无关）
```kotlin
// core/file-manager/src/commonMain/.../FileManagerModule.kt
data class FileManagerConfig(
    val enableFileEditor: Boolean = true,   // ≤2MB 文本编辑；>2MB 屏蔽
    val maxEditorBytes: Long = 2L * 1024 * 1024,
    val defaultDirectionWhenDrop: Map<DropSource, DropTarget, TransferDirection>
)

enum class Pane { Local, Remote }
data class PaneState(
    val cwd: String,
    val entries: List<SftpEntry>,        // 复用 SftpEntry
    val selection: Set<String> = emptySet(),
    val sort: SortKey = SortKey.Name,
    val order: SortOrder = SortOrder.Asc,
    val filter: String = "",
    val loading: Boolean = false,
    val errorMsg: String? = null,
)

interface SftpBackend {                  // 由宿主注入（KuiklyUI 端 = SftpModule；Electron = SftpGateway）
    suspend fun list(serverId: String, path: String): List<SftpEntry>
    suspend fun stat(serverId: String, path: String): SftpEntry?
    suspend fun mkdir(serverId: String, path: String)
    suspend fun rename(serverId: String, from: String, to: String)
    suspend fun remove(serverId: String, path: String, recursive: Boolean = false)
    suspend fun openFile(serverId: String, path: String, maxBytes: Long): ByteArray?  // ≤maxBytes 返回内容
    suspend fun writeFile(serverId: String, path: String, content: ByteArray)
}
interface LocalFsBackend {                 // 由宿主注入（KuiklyUI 端 = localfs:*；Electron = local-fs）
    fun home(): String
    fun list(dir: String): List<SftpEntry>
    fun stat(p: String): SftpEntry?
    fun mkdir(p: String); fun rename(from: String, to: String); fun remove(p: String, recursive: Boolean = false)
}

interface TransferGateway {                 // 调 packages/file-transfer 的薄壳
    fun start(req: TransferRequest): String   // taskId
    fun cancel(taskId: String); fun resume(taskId: String)
    fun resolveConflict(taskId: String, action: ConflictAction, newName: String?)
    fun list(): List<TransferTask>
    val progress: Flow<TransferProgressEvent>
    val conflict: Flow<TransferConflictEvent>
}

class FileManagerModule(          // 纯状态机：不做 IO
    val config: FileManagerConfig = FileManagerConfig(),
    val localRoot: String,
    val remotePane: PaneSpec,      // serverId + 初始 cwd
    val pathPolicy: PathPolicy = PathPolicy(localRoot),
    val conflictResolver: ConflictResolver = DefaultConflictResolver,
) {
    val state: StateFlow<FileManagerState>

    // 宿主喂数据（宿主异步 list / 传输后回填）
    fun setLoading(pane, loading); fun setEntries(pane, cwd, entries); fun setError(pane, msg)

    // 导航（纯）
    fun paneCwd(pane); fun childPath(pane, name); fun upPath(pane): String?
    fun navigateTo(pane, cwd); fun enter(pane, name): String?      // ".." 已到 root 返回 null

    // 选择 / 视图（纯）
    fun setSelection(pane, names); fun toggleSelection(pane, name)
    fun setFilter(pane, text); fun setSort(pane, key, order)

    // 操作计划（宿主执行）
    fun planMkdir(pane, name): String
    fun planRename(pane, from, to): Pair<String, String>
    fun planRemove(pane, names, recursive): List<String>           // 根目录保护
    fun planTransfer(items, direction): List<TransferRequest>      // 含记忆 overwrite
    fun planEditorSave(session, localTempPath): TransferRequest
    fun validateEditor(path, content): EditorSession?              // 禁用/超限/二进制 → null

    // 传输状态（宿主把引擎事件喂进来）
    fun onTransferStarted(taskId, req, bytesTotal)
    fun onTransferProgress(taskId, bytesSent, bytesTotal, currentFile)
    fun onTransferStatus(taskId, status, error?)
    fun clearTransfers()

    fun rememberAction(direction, action, forMs = 60_000)          // 记忆冲突选择
    fun shutdown()                                                  // 仅清核心状态；引擎/会话由宿主管
}
```

> **核心定位（2026-09 定稿）：纯状态机，不做 IO。**
> 本地列表（Electron `localfs:*` / 平台 API）、远端列表（`SftpModule`）、传输（传输引擎）
> **全部由宿主异步完成**，再把结果喂给核心（`setEntries` / `apply*` / `onTransfer*`）。
> 这样核心与「同步/异步」「哪个平台」彻底解耦；各端只换宿主实现。
> 后端接口（`LocalFsBackend` / `SftpBackend` / `TransferGateway`）属**宿主侧契约**，不在核心内。


### 2.4 跨平台设计（**开发以 Electron 为准，核心跨端复用**）

> **原则**：开发以 **Electron 宿主为桌面基线**（参考 `electron/test/smoke.mjs` 在打包版 10/10 的形态），但**核心与契约必须可跨端复用**——Android / iOS / HarmonyOS / Web 端只换 UI 与平台后端实现，不重写业务逻辑。

#### 2.4.1 分层与依赖方向
```
┌──────────────────────────────────────────────────────────┐
│ 渲染层（KMP Compose DSL）                                   │
│   demo/.../FilesDualPanePage.kt  ← 薄壳，仅消费 FileManagerModule  │
├──────────────────────────────────────────────────────────┤
│ 核心（KMP 公共层 / framework-agnostic）                       │
│   core/file-manager/                                       │
│   - FileManagerModule（State + Action + 操作编排）            │
│   - 依赖三个「宿主注入」抽象：SftpBackend / LocalFsBackend / TransferGateway  │
├──────────────────────────────────────────────────────────┤
│ 宿主层（每端实现后端抽象）                                   │
│   Android     : SftpModule(KRSftp*) + LocalFsModule + 调 packages/file-transfer │
│   iOS         : SftpModule(KRSftp*) + LocalFsModule + 调 packages/file-transfer │
│   macOS       : SftpModule(KRSftp*) + LocalFsModule + 调 packages/file-transfer │
│   HarmonyOS   : SftpModule(KRSftp*) + LocalFsModule + 调 packages/file-transfer │
│   Web(H5)     : SftpModule(走 sftp-gateway 远程) + LocalFsModule(本地) + 走 gateway │
│   Electron    : SftpModule(走 sftp-gateway) + LocalFsModule(本地 FS) + 走 packages/file-transfer │
└──────────────────────────────────────────────────────────┘
```

#### 2.4.2 各端适配（双栏的「本地 FS」与「远端 SFTP」与「传输引擎」三件事）
| 端 | 本地 FS 入口（"home"）| 远端 SFTP 入口 | 传输引擎 |
|---|---|---|---|
| Android | `context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath`（app 私有外部存储，与 `KRSftpModule` 一致）| `SftpModule`（已接 `jsch`）| `TransferGateway(TransferEngine, SftpBackend)` 调 `packages/file-transfer` 语义 |
| iOS | sandbox `Documents/`（`NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true).first`）| `SftpModule`（已接 NMSSH）| 同上 |
| macOS | `os.homedir()`（与 Janus 一致）| `SftpModule`（已接 NMSSH）| 同上（macOS 与 Electron 共用 `packages/file-transfer` 引擎）|
| HarmonyOS | `getContext().filesDir.path` | `SftpModule`（已接 libssh2）| 同上 |
| Web(H5) | **不提供左栏**：H5 无真实本地 FS；双栏变"远端单栏"（等同 `SftpBrowserPage`）| `__SFTP_GATEWAY_URL__` → `sftp-gateway` | `transfer:*` IPC → `packages/file-transfer`（Electron 主进程）|
| **Electron（基线）** | `app.getPath('home')`（推荐；比 `os.homedir()` 更尊重沙箱）| `SftpModule`（调 `sftp-gateway`）| `TransferEngine`（`packages/file-transfer`，Node/TS）|

> **Web 的"本地栏"决策**：默认不提供（双栏在 H5 退化为远端单栏）。若后续要补，候选方案是 **File System Access API 目录选择器**（用户选目录 → 浏览器授权持久读写；Chrome/Edge 支持，Safari/Firefox 不支持故需回退到"拖拽"或"无左栏"）。

#### 2.4.3 UI 层（KMP Compose DSL）
- `FilesDualPanePage.kt`（`demo/.../pages/sftp/`）是渲染层薄壳
  - 桌面/平板：**双栏并排**（Kuikly `Row` + 两栏 `LazyColumn`）
  - 手机：单栏（**点击「↔」切到对端**）—— 跨平台响应式
  - 跨栏操作：双栏拖拽 / 长按菜单 / 工具栏按钮 → 构造 `TransferRequest` → 调 `FileManagerModule.requestTransfer`
  - FileEditor：Kuikly Compose 弹层 `<2MB` 显示文本编辑器
- **不**写平台分支：`isAndroid / isElectron / ...` ❌（违反 AGENTS §1.3 I2/I3）

#### 2.4.4 跨平台测试矩阵
| 端 | L1 核心 | L2 渲染层集成 | L3 真实环境 |
|---|---|---|---|
| **Electron（基线，必过）** | commonTest | `electron/test/dual-pane.smoke.mjs`（CDP 驱动打包版 10/10 形态）| §7 全部 |
| Android | commonTest | `androidTest`（Compose UI 自动化）| 真机 / 模拟器 |
| iOS | commonTest | `iosTest`（同上）| 真机 / 模拟器 |
| HarmonyOS | commonTest | `ohtTest`（ArkUI）| 真机 |
| Web(H5) | commonTest | `sftp-web.test.js`（已有，仅单栏链路）| 浏览器 |

> **基线判断**：Electron L2 PASS ⇒ 契约成立；其他端跑 L1 + 该端 L2 即可视为跨端等价。

### 2.5 与现有 SFTP 客户端关系
- 现有 `SftpBrowserPage`（单栏）保留作为"全屏浏览"入口
- **双栏**是**新页面** `FilesDualPanePage`（在 `SftpHomePage` 增入口；侧栏可"跳到单栏"）
- 两者共用 `SftpModule`；双栏额外需要 `LocalFsBackend` 与 `TransferGateway`（与 `packages/file-transfer` 配套）

---

## 3. 测试分层与执行链路

| 层 | 名称 | 工具 | 自动/手动 | 何时跑 |
|---|---|---|---|---|
| **L0** | 模块不变量（§ 4） | `scripts/check-invariants.sh` | 自动 | 每次提交 |
| **L1** | 核心逻辑单测 | `kotlin-js` via Gradle `:core:file-manager:jvmTest` + `:jsTest` | 自动 | 每次提交 |
| **L2** | 渲染层集成（Web/Electron） | CDP（`electron/test/dual-pane.smoke.mjs`，与 `sftp-web.test.js` 同形态） | 自动 | 每次提交；§5 加进 `run-all-tests.sh` |
| **L3** | 真实 SFTP / 真实大目录 | Electron CDP 或真机 | 手动 | 每次发版前 |

---

## 4. L0 不变量（用 `check-invariants.sh` 校验，自动阻断违规）

| ID | 规则 |
|---|---|
| F1 | `core/file-manager` 不 import 任何 KuiklyUI 渲染层 / `compose` 内部 API（仅 `com.tencent.kuikly.core.*` 基础类型） |
| F2 | `core/file-manager` 不直接 `require('ssh2')` / 不直接做网络 IO（仅依赖宿主注入的 `SftpBackend` 接口） |
| F3 | `core/file-manager` 不直接 `require('electron')` / 不含 IPC 通道名（IPC 由宿主映射） |
| F4 | `core/file-manager` 不依赖 `packages/file-transfer` 的 Node API（仅依赖共同契约 `TransferRequest`/`ConflictAction` 等类型）—— 跨端跨栈共享 |
| F5 | `core/file-manager` 不复制 `SftpBrowserPage` 业务代码（调用 `SftpModule` 即可） |
| F6 | 双栏**不**做 >2MB 文本编辑（`maxEditorBytes` 默认 2 MiB） |
| F7 | 双栏**不**做双向实时同步（保持 Janus 限制） |
| F8 | 路径校验：禁止穿越 `localFs.root` 与 `sftp.root`（`PathPolicy` 单元覆盖） |
| **F9** | **跨端设计**：`core/file-manager` 不 import 任何 `Platform.isAndroid` / `Platform.isElectron` / `isBrowser` 等平台分支判断；适配差异必须走 `SftpBackend` / `LocalFsBackend` / `TransferGateway` 抽象 |
| **F10** | 双栏 `FilesDualPanePage` 渲染层不得含 `if (Platform.isXxx)` 分支；跨端响应式用布局属性（`Row` / `Column` / `BoxWithConstraints`）|

---

## 5. L1 单元测试用例（Kotlin/JS，commonTest 自动跑）

> 工具：`:core:file-manager:jvmTest`（JVM 跑得更快）+ `:core:file-manager:jsTest`（Node 跑，与 KMP JS 共享同一套 expect/assert）。
> 标记 `L1`；命名 `D-L1-xxx`。

### 5.1 状态与导航
| ID | 描述 | 步骤 | 预期 |
|---|---|---|---|
| `D-L1-01` | 双栏各自独立浏览 | `listLocal()` / `listRemote()` | 两栏 `cwd` 与 `entries` 互不污染 |
| `D-L1-02` | 双栏进入子目录 | `enter(Local, "subA")` | `localState.cwd` 更新为 `subA`；`remoteState.cwd` 不变 |
| `D-L1-03` | 双栏回到上级 | `up(Local)` | `localState.cwd` 回到父目录；边界 case = `cwd == root` → `errorMsg="已是顶层"`（**不**弹系统错误） |
| `D-L1-04` | 路径校验越界 | `enter(Local, "../escape")` 走 `PathPolicy.Default` | `errorMsg="路径越界"`；`entries` 不变 |
| `D-L1-05` | 排序按名称升序 | 列表含 `["b","A","c"]` + `sort=Name, order=Asc` | 排序后 `["A","b","c"]`（大小写不敏感，稳定） |
| `D-L1-06` | 过滤 | `setFilter(Local, "log")` | `entries` 仅保留名含 `log` 的大小写不敏感项 |
| `D-L1-07` | 加载态与错误态 | mock backend 抛 `IOException` | `loading=false`，`errorMsg` 含原因 |

### 5.2 选择
| ID | 描述 | 步骤 | 预期 |
|---|---|---|---|
| `D-L1-08` | 单选 | `toggleSelection(Local, "a.txt")` | `selection = {"a.txt"}` |
| `D-L1-09` | 多选 | 连续 toggle 三项 | 三项都在 `selection` |
| `D-L1-10` | 进入目录清空选择 | `enter(Local, "subA")` | 选择集清空（防止跨目录误操作） |
| `D-L1-11` | 操作前置条件 | 选空调用 `requestTransfer` | 抛 `IllegalStateException("无选中项")`；不调传输 |

### 5.3 传输（核心）
| ID | 描述 | 步骤 | 预期 |
|---|---|---|---|
| `D-L1-12` | 本地→远端 选区 → TransferRequest（upload） | Local 选 `[a, b, c]`、`requestTransfer(direction=Upload)` | 返回 taskId；`transfer.start` 收到 `direction=Upload, localPaths=[a,b,c], remotePath=<join(remote.cwd, name)>`；目录项 `isDir=true` |
| `D-L1-13` | 远端→本地 选区 → TransferRequest（download） | Remote 选 `[x.txt]`, `requestTransfer(direction=Download)` | `direction=Download, localPath=join(local.cwd, "x.txt"), remotePath="x.txt"` |
| `D-L1-14` | 冲突尺寸不一致 → 询问宿主 | mock backend 报 `size mismatch`、调用 `transfer.resolveConflict` 之前 task 处于 `waiting-conflict` | `state.transfers[taskId].status == WaitingConflict`；resolve 后 `Resolved` |
| `D-L1-15` | 记忆的冲突选择（同向同路径） | `rememberAction(Upload, Overwrite, 60_000)`；再发起同向同路径 → resolver 直接返回 Overwrite（不弹窗） |
| `D-L1-16` | 记忆超期 | 60s 后再次同向同路径 → 回到弹窗 | 解析器被调用 |
| `D-L1-17` | 取消 | `cancel(taskId)` | 引擎标记 `Canceled`（mock 收到 `transfer:cancel`） |
| `D-L1-18` | 续传 | `resume(taskId)` | mock backend 在 `cancel` 后重入 → 任务 `Running`；`item.offset > 0` 续传 |
| `D-L1-19` | 进度事件 | 收集 `transfer.progress` 流 | 至少 1 个 `TransferProgressEvent(taskId, bytesSent>0, bytesTotal>0)` |
| `D-L1-20` | 队列容量 | 构造 4 个 `requestTransfer` + `maxConcurrent=3`（依赖引擎默认值）| 3 个在 `Running`/`Scanning`，1 个在 `Queued`；3 个完成后第 4 个进入 `Running` |
| `D-L1-21` | shutdown 幂等 | `shutdown()` 两次 | 第二次不抛异常；引擎 `state.transfers` 清空，引擎不再 emit 事件 |
| `D-L1-22` | shutdown 取消所有 | 进行中 3 个 + `shutdown()` | 全部最终状态 `Canceled` |
| `D-L1-23` | 同尺寸文件"已传输"跳过 | mock backend 报告 `equalSize` → resolver 返回 `Skip` → 不调实际 `transfer.start` | `state.transfers` 不新增；列表保持 |

### 5.4 路径与安全
| ID | 描述 | 步骤 | 预期 |
|---|---|---|---|
| `D-L1-24` | Local 路径越 root | `enter(Local, "../")` | 抛 / `errorMsg="路径越界"`（F8） |
| `D-L1-25` | Remote 路径含 `..` | `enter(Remote, "a/../../b")` | 拒绝 |
| `D-L1-26` | Remote 绝对路径 | `enter(Remote, "/etc/passwd")` | 拒绝（必须相对 remotePaneId.cwd） |
| `D-L1-27` | Local 符号链接 | mock `localFs.lstat` 返回 `isSymbolicLink=true, isFile=true` | `entries` 含 symlink 条目（**不**跟随 stat） |

### 5.5 FileEditor（≤2MB 文本编辑）
| ID | 描述 | 步骤 | 预期 |
|---|---|---|---|
| `D-L1-28` | 打开 ≤maxEditorBytes 文本 | `openEditor(Remote, "note.txt")` 文件 1KB 文本 | 返回非 null `EditorSession(content, encoding)`；UTF-8 解码 |
| `D-L1-29` | 打开 >maxEditorBytes 阻止 | 5MB 二进制文件 | 返回 `null`；不抛错（F6） |
| `D-L1-30` | 保存 → 走 transfer upload | `saveEditor(session, newContent)` | mock `transfer.start` 收到 `direction=Upload, localPath=<tmp>, remotePath=<original remote path>`；本地 tmp 文件存在并含 newContent |
| `D-L1-31` | 编辑期间断网 | `saveEditor` 时 transfer 抛错 | `state.error` 含原因；临时文件清理（不留垃圾） |
| `D-L1-32` | 编码 | 二进制文件（mock 字节含 NUL/高字节） | `openEditor` 返回 `null`（避免乱码） |

### 5.6 文件操作
| ID | 描述 | 步骤 | 预期 |
|---|---|---|---|
| `D-L1-33` | mkdir | `mkdir(Local, "newDir")` | `localFs.mkdir` 被调；`refresh()` 后 `entries` 含 `newDir` |
| `D-L1-34` | rename（无冲突）| `rename(Local, "a", "b")` | `entries` 旧名换新名；选中集同步更新 |
| `D-L1-35` | rename（目标存在） | `rename(Local, "a", "existing")` | 抛错；`state.localPane.errorMsg` 提示冲突；`refresh()` 不变 |
| `D-L1-36` | remove 单文件 | `remove(Local, ["a"])` | `entries` 移除；`selection` 也移除 |
| `D-L1-37` | remove 文件夹（递归）| `remove(Local, ["dir"], recursive=true)` | mock `localFs.remove(p, true)` 被调 |
| `D-L1-38` | 路径越界操作 | `mkdir(Local, "../escape")` | 抛错（不调 backend） |
| `D-L1-39` | 远端删除保护 | `remove(Remote, ["/"])` | 拒绝（保护根目录） |

### 5.7 默认值与边界
| ID | 描述 | 预期 |
|---|---|---|
| `D-L1-40` | 默认 `maxConcurrent=3` 与 `file-transfer` 模块一致 | `cfg` 不传 `maxConcurrent` 时 `module.queue` 上限 = 3 |
| `D-L1-41` | 默认 `chunkSize` / `retryBackoff` 一致 | 同 `packages/file-transfer` 默认 |
| `D-L1-42` | `enableFileEditor=false` 时 `openEditor` 始终 null | 即使 ≤2MB 也返回 null |

---

## 6. L2 渲染层集成测试（**主跑：Electron 宿主 + CDP**，自动）

> **以 Electron 那个版本为准**（参照 `electron/test/smoke.mjs` 在打包版 10/10 的形态）。工具：`electron/test/dual-pane.smoke.mjs`，与既有 SFTP smoke 共用 CDP 驱动 + 包内主页路由。命令：`ELECTRON_TEST_BIN="dist/mac/Kuikly SFTP.app/Contents/MacOS/Kuikly SFTP" node electron/test/dual-pane.smoke.mjs`，或开发态 `npm run start` 后 CDP 连接。H5 harness 只覆盖 SFTP 单栏链路。

> 工具：与 `sftp-web.test.js` 同形态 — `electron/test/dual-pane.smoke.mjs`。
> 命令：`bash scripts/run-all-tests.sh` 增量加入 `L2_DUAL_PANE`（默认开启，可用 `SKIP_DUAL_PANE=1` 跳过）。
> 标记 `D-L2-xxx`。

### 6.1 启动与布局
| ID | 描述 | 步骤 | 预期 |
|---|---|---|---|
| `D-L2-01` | 双栏页加载 | `?page_name=FilesDualPanePage&localPath=/tmp&serverId=...&remotePath=/home/zhaojian` | 出现「本地」与「远端」标题；两栏 `entries` 渲染（各 ≥0 项） |
| `D-L2-02` | 远端列表含真实文件 | 默认 `serverId` 指向测试服务器 | 远端栏含 `sftp_kuikly_media.mp4` 等 |
| `D-L2-03` | 控件台 | DOM 存在 [上传/下载/新建/重命名/删除] 按钮 + 工具栏 | 至少 5 个按钮可见 |

### 6.2 浏览与选择
| ID | 描述 | 步骤 | 预期 |
|---|---|---|---|
| `D-L2-04` | 双击进入 | 双击远端 `kr_webtest_sort`（CDP `Input.dispatchMouseEvent` 双击） | 远端 `cwd` 变深一层，列表刷新 |
| `D-L2-05` | 单选 | 在远端条目上 mousedown | 选中集高亮（CSS / 标记） |
| `D-L2-06` | 多选（ctrl） | Ctrl+点击多项 | 多项高亮 |
| `D-L2-07` | 工具栏「上传」 | 选本地文件 → 点击上传 | `transfer:start` 事件被宿主收到（mock IPC 计数 > 0） |
| `D-L2-08` | 工具栏「下载」 | 选远端文件 → 点击下载 | 同上 |

### 6.3 拖拽
| ID | 描述 | 步骤 | 预期 |
|---|---|---|---|
| `D-L2-09` | 拖拽本地→远端 | CDP `Input.dispatchMouseEvent` 模拟 mousedown→moves→mouseup 从本地条目到远端栏 | `transfer:start` 收到 `direction='upload'`，`localPath` 与 `remotePath` 正确 |
| `D-L2-10` | 拖拽到错误目标 | 拖到非文件项 / 边界外 | 无 `transfer:start`；无错误弹窗（仅提示一次） |
| `D-L2-11` | 跨栏拖拽多次 | 连续 3 次 | 3 个 `transfer:start` |

### 6.4 冲突 UX
| ID | 描述 | 步骤 | 预期 |
|---|---|---|---|
| `D-L2-12` | 触发冲突弹窗 | 触发 `ConflictEvent`（mock backend size 不一致） | UI 出现「覆盖 / 改名 / 跳过 / 取消」按钮 |
| `D-L2-13` | 选「覆盖」 | 点击 | `transfer:resolve` 被调 `Overwrite` |
| `D-L2-14` | 记忆选择 | 选「覆盖」+ 选「记住此选择 60 秒」 | 下次同向同路径直接覆盖（不弹窗） |
| `D-L2-15` | 选「跳过」 | 点击 | `state.transfers[taskId].status` 变 Done/部分完成；目标不写 |

### 6.5 进度反馈
| ID | 描述 | 步骤 | 预期 |
|---|---|---|---|
| `D-L2-16` | 传输进度事件流 | 启动一个 ≥1MB 文件传输；订阅 `transfer:progress` 60s | 至少 1 个事件，`bytesSent > 0` |
| `D-L2-17` | 队列显示 | 队列 UI（`TransferQueue`）展示 task 名/方向/进度 | 与 `state.transfers` 一致 |
| `D-L2-18` | 终态通知（Janus F3 移植） | 任务完成 | 触发系统通知（mock `Notification.isSupported()`） |

### 6.6 错误态与韧性
| ID | 描述 | 步骤 | 预期 |
|---|---|---|---|
| `D-L2-19` | 断网 | mock backend 抛 `ECONNRESET` 一次 | 任务进入 `interrupted` → 自动重试（按 `retryBackoff`） |
| `D-L2-20` | 重试超限 | 第 6 次失败 | 任务 `error`；队列显示错误信息 |
| `D-L2-21` | 引擎崩溃后 | 调 `engine.shutdown()` | UI 不再收到 `transfer:progress`；队列清空（与 §L1-21/22 对齐） |
| `D-L2-22` | 鉴权失败 | `sftp.list` 抛鉴权错 | 远端栏 `errorMsg="鉴权失败"`；双栏不崩溃 |
| `D-L2-23` | 远端 404 路径 | 删了某路径后 list | 错误提示；不无限重试 |

---

## 7. L3 真实环境手动用例（人眼/真机，发版前必跑）

> 工具：手动 / Electron CDP（`ELECTRON_TEST_BIN=…`），按 `devDocs/sftp-test-plan.md` §3.5 形态。
> 标记 `D-L3-xxx`；不可自动化（真实体验 / 大目录 / 物理交互），发版前必须勾选并签字。

| ID | 描述 | 步骤 | 预期 |
|---|---|---|---|
| `D-L3-01` | 真实大目录（≥10k 项）| 打开含大量文件的目录 | 列表滚动流畅，搜索/过滤无卡顿；选 100 项→上传→全部进入队列 |
| `D-L3-02` | 真实大文件（≥1GB）| 上传/下载 1GB+ 文件 | 进度条推进；拖动进度到中间 → 实际位置更新；可暂停/续传 |
| `D-L3-03` | 物理拖拽 | OS 文件管理器拖文件到双栏 | 触发 `transfer:start`；同 `D-L2-09` |
| `D-L3-04` | 远程编辑大文本文档 | 远程 1MB 文本（≤2MB） | 编辑→保存→远端实际更新；版本对比可见 |
| `D-L3-05` | 远程二进制文件 | 远程 5MB .bin | 「>2MB 不支持」提示；打开按钮置灰或弹提示 |
| `D-L3-06` | 慢网 | 限速到 100KB/s | 上传大文件，进度稳步推进；可取消；取消后剩余不写 |
| `D-L3-07` | 跨会话 | 关闭应用重开 | `state.transfers` 丢失（除非 `persist`）；`rememberedActions` 由宿主持久化 |
| `D-L3-08` | 凭据轮换 | 修改 SFTP 密码 | 已连接任务用旧凭据继续；新任务用新凭据 |
| `D-L3-09` | 跨平台 | macOS / Windows / iOS | 双栏布局/操作一致（视觉差异可接受） |
| `D-L3-10` | 无障碍 | VoiceOver / TalkBack | 焦点/标签可读；键盘可达所有操作 |
| `D-L3-11` | 记忆冲突策略 | 选「记住此选择 → 跳过」+ 60s 内重复 | 第二次直接跳过；不弹窗 |

---

## 8. 一键执行链路

将双栏测试加入既有 `scripts/run-all-tests.sh`。**主路径 = Electron 宿主**（参照 `electron/test/smoke.mjs` 10/10）：

```bash
bash scripts/run-all-tests.sh   # 全量：L0/L1/L2/L3 + Electron 双栏 CDP（默认开启）
SKIP_DUAL_PANE=1 bash scripts/run-all-tests.sh   # 跳过双栏 CDP（开发未完成时）
SKIP_ELECTRON=1 bash scripts/run-all-tests.sh     # 同时跳过 Electron（仅跑 L0/L1）
```

具体新增条目：
- `L0` 不变量：增加 §4 规则（合并到 `scripts/check-invariants.sh`，自动）
- `L1` 单测：`./gradlew :core:file-manager:jvmTest :core:file-manager:jsTest`（开发完一次性加入）
- `L2` 集成（**主跑 Electron**）：`electron/test/dual-pane.smoke.mjs`，参照 `electron/test/smoke.mjs` 形态，CDP 驱动打包版 `dist/mac/Kuikly SFTP.app`（`ELECTRON_TEST_BIN=…`）。H5 harness 只覆盖 SFTP 单栏链路
- `L3` 手动：人工执行（本文件 §7 + `devDocs/sftp-test-plan.md` §3.5 的样例）

## 9. 验收门禁（DoD）

一项「双栏文件管理器」开发完成的判定 = **§4 不变量 + §5 L1 + §6 L2 Electron 打包版 CDP 双栏冒烟全部 PASS**（§7 L3 人工签字），并已并入 `bash scripts/run-all-tests.sh` 一键全绿。**任何子项失败**禁止合入主分支。

---

## 10. 与 `packages/file-transfer` 的边界
| 双栏模块 | 传输模块 |
|---|---|
| 选区 → 构造 `TransferRequest` | 接收 `TransferRequest` → 实际 IO |
| `transfer:start/resolveConflict` 触发 | 内部队列 / 断点 / 重试 / 事件 |
| 不知道 `ConflictAction` 的具体落盘 | 落盘 / 偏移记录 |
| 不依赖 `ssh2` | `SftpBackend` 依赖 `ssh2`（`optionalDependencies`）|
| 测试不需 ssh2（用 mock `SftpBackend` / `LocalFsBackend`）| 集成测试可起真实网关 |

---

## 11. 下一步（等你确认）
1. 本测试计划是否覆盖到位？是否还有遗漏场景（特别是 §7 L3 真实环境）？
2. §2.3 包结构（`core/file-manager` 核心模块 + `demo/.../FilesDualPanePage` 渲染）是否符合预期？
3. 是否有特殊平台限制（iOS / Android 双栏 vs KuiklyUI 已有 SftpBrowserPage 范式）需额外用例？

确认后 → 按 §2/§3 落地 M1（核心模块 + L1），M2（渲染层 + L2），M3（回归 + L3 签字）。


---

## 7. 实测用例（D0–D25，已 28/28 通过）

> 运行：`cd electron && npm run build:web`（或先跑 Gradle 打包）→ `npm run sync` → `npm run test:dual`
> 脚本：`electron/test/dual-pane.mjs`；截图：`electron/test/artifacts/*.png`（关键步骤自动存图）
> 前置：外部网关已起：`cd electron && npm run gateway`（端口按实例计算；脚本连它做断言/清理；Electron 主进程另起自带网关供页面使用）
> **夹具自建自清**：本地夹具建在 `.kr-test/local-<instance>/000_kuikly_dual_<instance>.*`（经 `KR_LOCAL_ROOT` 隔离），
> 远端夹具名带实例前缀；结束时全部删除（不留残余）。并行 worktree 说明见 `electron/test/env.mjs` 与 `AGENTS.md §3.1 规则 13`。

| ID | 用例 | 断言方式 |
|----|------|----------|
| D0 | 网关连接真实服务器 | RPC `connect` 返回 sessionId |
| D1 | Electron 可见窗口 + CDP | `/json/version` |
| D2–D4 | 双栏渲染；本地栏列出真实主目录；远端栏列出真实远端目录 | 页面文本包含真实条目 |
| D5 | **真实点击**本地文件 → 选中 1 项 | 状态行「本地已选 1 项」 |
| D6–D7 | 点击「上传 →」；**字节级**校验远端内容 | 远端 `stat` + `openRead/read` 与本地文件 `equals` |
| D8 | 256KB 随机二进制上传**字节级**一致 | 同上 |
| D9–D10 | 点击「← 下载」；**字节级**校验本地文件 | `fs.readFileSync` 与远端内容 `equals` |
| D11–D12 | 远端栏「+」→ 弹层 → 键入名称 → 确定 → 远端真实建目录 | 真实点击 + `Input` 键入 + RPC `stat` |
| D13 | 点击目录行「▶」→ 进入下一级 | 远端栏路径变为 `<home>/<new>` |
| D14a/b/c | 「↑」返回上级 → 行点击选中 → 点「删除」出确认弹层 | 路径回退 + 状态行「远端已选 1 项」+ 弹层文案 |
| D15 | 确认删除 → 远端目录消失 | RPC `stat` 失败 |
| D17 | 首页出现默认「本地文件管理」入口 | 页面文本 |
| D18 | 点击后进入双栏（本地可用、远端待选主机） | 文本含「未连接远端」 |
| D19 | 浏览页右上角「⇄」→ 双栏且远端栏定位当前目录 | 路径出现 |
| D16 | 无 JS 未捕获异常 | `Runtime.exceptionThrown` 计数为 0 |
| D20 | **网关拒绝越界 `localPath` 上传** | RPC 返回 `code=2001`（`LOCAL_PATH_DENIED`）|
| D21 | **网关拒绝越界绝对路径下载，且未落盘** | `code=2001` 且 `/etc/...` 不存在 |
| D22 | 根内 `localPath` 上传不被误拒 | 上传成功 |
| D23 | 本地栏「+」新建目录（活动栏默认本地）| 本地真实出现目录 |
| D24 | 本地栏删除目录 | 本地目录真实消失 |
| D25 | **双栏 URL 不含凭据** | `location.href` 无 `password=`/`privateKey=`/`passphrase=` |

### 7.1 真实点击技法（CDP，已验证可用）

- **`clickText(txt)`**：取「文本包含 txt 且可见」的**最小**元素，派发 `Input.dispatchMouseEvent`（mousePressed → 60ms → mouseReleased）。
  早期结论「CDP 点不动 Kuikly Scroller 列表项」**不成立**：列表行内的文件/目录名点击均可命中（D5/D13/D14b）。
- **`clickIn(txt, anc)`**：在「第一个包含 `anc` 的祖先」范围内点 `txt`，用于区分左右两栏同名按钮（`+`/`↑`/`删除`）。
  ⚠️ 祖先必须限制层级并校验**文本长度**：否则会命中整个滚动容器/页面根，把「远端栏的 +」误判成「本地栏的 +」。
- **`clickRow(name)`**：按「`▶` 的父元素包含 name」定位行，点行左侧名称区。
  用于避免 `clickText(name)` 误命中**状态栏文字**（例如状态行「已新建 <name>」比行内名称更短、更小 → 被优先选中）。
- **`typeInto(text)`**：先真实点击输入框（Kuikly Web `Input` 就是真实 `<input>`，监听 `input`），
  优先真实按键（`Input.dispatchKeyEvent` keyDown/keyUp 逐字符），兜底写 `value` 并派发 `input` 事件（等价输入法插入）。

### 7.2 本轮踩到并修掉的问题（勿回退）

1. **依赖收集**：`loading`/`cwd` 等**不能当普通参数**传进子渲染函数，必须用 provider 并在 `attr {}` / `vif` 条件内读取；否则数据回来了也不重渲染（页面永远「加载中」/路径为空）。参见 AGENTS §13.4 第 1 条。
2. **分隔线用了 `flex(1f)`** → 在 Row 里吃掉 1 份宽度，界面变「三栏」。分隔线只能 `width(1f)`，父 Row 用 `alignItemsStretch()`。
3. **栏内 `Scroller` 未限宽** → 行按整页宽渲染、右侧大小列与 `▶` 被分隔线裁掉（「列表显示不全」）。每栏必须 `width((pageViewWidth - 1)/2)`，行再减 8px 预留滚动条。
4. **工具条未绑定活动栏** → 在远端选中却删了本地同名文件。已加活动栏（`●`）语义。
5. **目录不可选中**（行点击即进入）→ 无法删除/重命名目录。已拆为「行点击=选中，`▶`=进入」。


---

## 8. 安全模型（本轮修复后，勿回退）

双栏要读写**本机文件**，因此本地路径访问有三道闸门：

1. **页面/宿主（Electron IPC）**：`window.localFs.*` 只接受 `LOCAL_ROOT = app.getPath('home')` 之下、
   且 **realpath 解析后**仍在根内的路径（防主目录内的符号链接指向外部）；`writeFile` 额外拒绝目标本身是符号链接。
2. **网关（`sftp-gateway`，浏览器/桌面共用）**：`upload` 的 `localPath` 与 `download` 的绝对路径目标
   同样限制在 `SFTP_GATEWAY_LOCAL_ROOT`（默认 `os.homedir()`，Electron 显式传入 `app.getPath('home')`）之下，
   越界抛 `LOCAL_PATH_DENIED` → 错误码 **2001**；上传改为**流式**（不再整份读入内存）。
3. **凭据不进 URL**：路由只用 `connectionId`（SPA 会把 pageData 拼进 query → `history.pushState`）。
   浏览页的「⇄」在只有内联凭据时，先把连接写入连接库（加密存储）再用 id 打开。

> 注意：网关仍**无鉴权且 `Access-Control-Allow-Origin: *`**（仅绑回环）。上述闸门把可利用面收敛为
> 「根内的本地文件读写」，但**给 `/rpc` 加一次性 token** 仍待做（记为后续项）。
