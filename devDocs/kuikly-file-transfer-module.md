# Kuikly 跨端项目：文件双向传输模块 —— 抽离与开发计划（决策稿）

> **本文件是「决定做不做 / 怎么做 / 加什么功能」的决策稿**，不是已落地的实现代码。
> 实现（`packages/file-transfer/*`）待你确认方案后由 M1 起按 §5 逐步落地。
>
> 关联：
> - **Janus 仓库**：`https://github.com/AdrianAndroid/janus`（本地路径 `/Users/zhaojian/bin/macmini/janus`）
>   - **要参考的分支：`zhaojian`**（包含双栏文件管理器 + 传输引擎；**10 个提交领先于上游 `main`**）
>   - **上游基线 `main` = Janus 1.9.0**：没有传输能力；不要参考
>   - **传输能力的来源提交（zhaojian 上）**：`e3ba0e1 添加双栏`（一次性引入 `src/main/transfer-manager.ts`、`local-fs.ts`、传输/localfs IPC、FilesPanel 等）
>   - 本仓库（KuiklyUI）里新建 `packages/file-transfer` 时，**所有 Janus 内的引用都应指向 `zhaojian` 分支（不要 checkout 到 `main`）**
> - Janus 关键实现（`MODIFICATIONS.md §5` + `docs/practical-features-implementation-plan.md`）：
>   - 引擎：`src/main/transfer-manager.ts`（467 行，3 并发、1 MiB 分块、字节偏移断点续传、冲突协商、文件夹递归、断线自动重试退避 3→5→10→20→30s 最多 5 次、shut-down 取消全部、~4Hz 节流上报）
>   - 本地 FS：`src/main/local-fs.ts`（home / list / mkdir / rename / remove / stat / dir-size）
>   - SSH 增强：`src/main/ssh-manager.ts`（`getSftp` / `sftpStat` / `sftpMakedirs` / `sftpRemoveRecursive` / `sftpDirSize`）
>   - IPC 通道：`src/shared/ipc.ts` + `src/main/ipc.ts` 的 `localfs:*`（7）+ `transfer:*`（6 调用 + 2 事件）
>   - 桥接：`src/preload/index.ts` 暴露 `window.localFs.*` / `window.transfer.*`
>   - 类型：`src/shared/types.ts` `TransferTask/TransferRequest/TransferStatus/TransferConflict/ConflictAction/TransferDirection`
>   - UI：`src/renderer/src/FilesPanel.tsx`（双栏 + 传输编排 + FileEditor 远程编辑，>2MB 不支持）+ store `transfers/conflicts/rememberedActions`

---

## 0. TL;DR 与待你确认

**我建议**：把 Janus 的文件双向传输能力抽离为 KuiklyUI 仓库下一个**独立 Node 包** `packages/file-transfer`，**纯框架无关**（无 Electron / 无 IPC 依赖），仅通过「`TransferBackend` 抽象 + 事件发射回调」与宿主交互；默认提供 `SftpBackend`（依赖 `ssh2` 作为 optionalDependency），并提供 `LocalBackend`（同主机两目录拷贝，给测试用）。KuiklyUI 自身的 `electron/main.js` 通过 `file:../packages/file-transfer` 依赖、在主进程起 engine、桥接 IPC `transfer:*` 给渲染层；KMP / commonRender **不沾**（传输是桌面宿主能力）。

**待你拍板/补充**（§8 详列）：
- M1 必做范围
- 候选功能（你可能想加的）
- 与 Janus `ConflictAction` 对齐（是否引入 `'resume'`）
- 本地 FS 范围（是放模块里还是留在宿主）
- 错误模型
- 性能与并发默认

---

## 1. 调研：Janus 传输引擎具体语义

以下是从 Janus 源码读出的**精确行为**（决定了我们要不要原样照搬）。

### 1.1 任务模型与并发
- 单实例 `TransferManager`，持有 `tasks: Map<string, InternalTask>`，**并发上限 `MAX_CONCURRENT = 3`**（构造时已固定）。
- `pump()` 推进队列：扫到 `status === 'queued'` 且 `!cancelFlag` 的任务，运行；用 `runningCount()` 跟踪。
- `emitTask(t, force = false)` 节流到约 **4Hz**（避免渲染层被事件淹没），`force=true` 终态时强制刷一次。

### 1.2 文件清单（scan 阶段）
- **upload**（本地→远端）：遍历本地目录，**跟随文件符号链接**（`stat(lp)`，`st.isFile()` 视为文件），**跳过目录符号链接**（防循环）；把每一层远程目录 `sftpMakedirs`。
- **download**（远端→本地）：用 `sftp.readdir` 走远端，**跳过 `.`/`..`，且跳过 `isSymbolicLink` 项**（无论文件还是目录都不跟随）—— 区别于 upload 的策略**。
- `dirs` / `localDirs` 集合在递归结束后用于一次性 `mkdir` 全部父目录。

### 1.3 分块流式复制（copy 阶段，字节偏移断点续传）
- **upload**：`createReadStream(local, { start: offset, highWaterMark: 1 MiB })` + `sftp.createWriteStream(remote, offset>0 ? {flags:'r+', start:offset} : {flags:'w'})`
- **download**：`sftp.createReadStream(remote, { start: offset })` + `createWriteStream(local, offset>0 ? {flags:'r+', start:offset} : {flags:'w'})`（预先 `mkdir -p` 父目录）
- 写入成功后：`item.offset += chunk.length` 并 `emitTask` 进度。
- **错误分类**：`ECONNRESET|ECONNREFUSED|ETIMEDOUT|EPIPE|connection lost/reset/no response` 等映射为 `INTERRUPTED`（触发自动重试）；`CANCELED` 用于取消；其余透传。
- **`t.destroyActive`**：cancel 时调用，关闭源/汇流并 reject（用于在 cancel 中断 `pipe` 而不让 Node 抛 `ECONNRESET`）。

### 1.4 冲突与决策（IPC `transfer:conflict`）
- `ConflictAction = 'resume' | 'overwrite' | 'skip' | 'rename'`
  - `'resume'`：**部分下载续传**——远端已有更大文件时选择覆盖该段（这是 Janus 比常见 3 选 1 多出的一个动作）。
  - `'overwrite'` 直接覆盖；`'skip'` 跳过；`'rename'` 改名后写入；`'cancel'` 取消整个任务。
- `targetSize(item)` 询问远端/本地大小用于续传定位；`applyRename(item, newName)` 改写 `item.remote`。
- 决定由宿主经 `transfer:resolve` 事件回传（renderer UI 弹窗 → `window.transfer.resolveConflict(id, action)`）。

### 1.5 重试与中断恢复
- 状态机：`queued / scanning / running / waiting-conflict / interrupted / done / error / canceled`
- `interrupted` 状态保留 `retryAttempts`，按 `RETRY_BACKOFF_MS = [3k, 5k, 10k, 20k, 30k]` 退避重排，**最多 `RETRY_MAX = 5` 次**。
- 每次重试前 `error = "${err} — auto-resume attempt k/5…"` 推送给 UI。
- 手动 `resume(id)` 立即重置计数并入队。
- `vaultLock` 关闭 / 应用退出时 → `engine.shutdown()` 必须**取消全部任务、清空定时器、关闭 backend session**（Janus F5 修复点）。

### 1.6 IPC 通道（Janus 现行）
| 通道 | 方向 | 用途 |
|---|---|---|
| `localfs:home` | renderer→main | `Promise<string>` 本地 home |
| `localfs:list` | → | `{cwd, entries: SftpEntry[]}` |
| `localfs:mkdir` / `rename` / `remove` / `stat` / `dir-size` | → | 各自 Promise |
| `transfer:start` | → | `Promise<taskId>` |
| `transfer:cancel` / `transfer:resume` | → | `Promise<boolean>` |
| `transfer:resolve` | → | `Promise<boolean>` 提交冲突决策（`action`+`newName?`）|
| `transfer:clear` / `transfer:list` | → | 全部任务 / `TransferTask[]` |
| `transfer:progress` | main→renderer | 进度事件 |
| `transfer:conflict` | main→renderer | 弹窗事件，渲染层回 `resolve` |

### 1.7 本地 FS（不在本模块的职责）
- `localHome/list/mkdir/rename/remove/stat/dir-size` 留在宿主侧（KuiklyUI 已在 `electron/main.js` / `preload.js` 自管）。
- **本模块不接触本地 FS**——只接受 `localPath: string`，宿主把"本地"概念具象化（与 Janus 一致）。

---

## 2. KuiklyUI 现有集成点

| 位置 | 现状 | 接入本模块的位置 |
|---|---|---|
| `electron/main.js` | 仅托管 `sftp-gateway`（utilityProcess.fork + 端口回传 + 注入 `__SFTP_GATEWAY_URL__`） | 在此**新增 engine 实例**（`createTransferEngine`），桥接 `transfer:*` IPC |
| `electron/preload.js` | 已注入网关 URL + `kuiklyHost`（saveFile/notify/getInfo） | 暴露 `window.transfer`（start/cancel/resume/resolve/list + progress/conflict 订阅） |
| `electron/test/smoke.mjs` | 仅验证 SFTP + 播放 | 加一条「传输冒烟」（可选，不阻塞 CI）|
| `electron/package.json` | 当前 `dependencies: ssh2` | 新增 `"@kuikly/file-transfer": "file:../packages/file-transfer"` |
| `sftp-gateway/` | 与本模块正交（可独立存在） | 默认**不**消费本模块；如需可独立引用 |
| KMP / commonRender | — | **不沾**（传输是桌面宿主能力） |

---

## 3. 方案设计

### 3.1 模块边界与契约（核心红线）
- **不依赖** `electron` / IPC / DOM / KuiklyUI；纯 Node + 可插拔 `TransportBackend`。
- 公共 API（草案，可微调）：
  ```ts
  // packages/file-transfer/src/types.ts
  export type TransferDirection = 'upload' | 'download'
  export type TransferStatus =
    | 'queued' | 'scanning' | 'running'
    | 'waiting-conflict' | 'interrupted'
    | 'done' | 'error' | 'canceled'
  /** 与 Janus 对齐：含 'resume'（部分下载续传） */
  export type ConflictAction = 'resume' | 'overwrite' | 'skip' | 'rename'

  export interface TransferRequest {
    taskId?: string
    serverId: string
    direction: TransferDirection
    localPath: string
    remotePath: string
    isDir?: boolean
    chunkSize?: number          // 默认 1 MiB
    overwrite?: boolean        // 默认 false（false 走 conflict 流程）
  }
  export interface TransferFileItem {
    local: string; remote: string
    size: number; offset: number; done: boolean
  }
  export interface TransferTask {
    id: string
    serverId: string
    direction: TransferDirection
    name: string
    status: TransferStatus
    bytesTotal: number; bytesSent: number
    currentFile?: string
    error?: string
    startedAt: number; finishedAt?: number
    retryAttempts: number
    files?: TransferFileItem[]
  }
  ```
  ```ts
  // packages/file-transfer/src/backends/types.ts
  export interface BackendEntry { name; type:'file'|'dir'|'symlink'|'other'; size; mtime; mode?; owner?; group? }
  export type ConflictResolver = (remotePath, info) => Promise<ConflictAction & { newName?: string }>
  export interface BackendSession {
    list(dir): Promise<BackendEntry[]>
    stat(p): Promise<BackendEntry | null>
    uploadFiles(items, conflict): AsyncIterable<TransferFileItem>
    downloadFiles(items, conflict): AsyncIterable<TransferFileItem>
    remove(p, recursive?): Promise<void>
    mkdirp(p): Promise<void>
    close(): Promise<void>
  }
  export interface TransportBackend { acquire(serverId): Promise<BackendSession> }
  ```
  ```ts
  // packages/file-transfer/src/engine.ts
  export class TransferEngine {
    start(req: TransferRequest): Promise<string>
    cancel(id): Promise<void>
    resume(id): Promise<void>
    resolveConflict(id, action): void
    clear(): Promise<void>                  // 清空全部
    list(): TransferTask[]
    activePaths(): { serverId; direction; localPath; remotePath }[]
    shutdown(): Promise<void>               // 取消全部 + 关 backend
    onChange(cb: () => void): () => void
  }
  export const DEFAULT_MAX_CONCURRENT = 3
  export const DEFAULT_CHUNK_SIZE = 1 << 20
  export const DEFAULT_RETRY_BACKOFF_MS = [3_000, 5_000, 10_000, 20_000, 30_000]
  export const DEFAULT_RETRY_MAX = 5
  export function createTransferEngine(opts: TransferEngineOptions): TransferEngine
  ```
- 事件发射：`opts.emit(channel: string, payload)` 透传（不耦合到具体 IPC 名）。
- 后端默认实现：
  - `SftpBackend`：依赖 `ssh2`（`optionalDependencies`），按 Janus 语义提供 session。
  - `LocalBackend`：进程内两目录拷贝，给测试 / 同主机场景用。

### 3.2 与 Janus 的差异点（**请确认这些取舍**）
| 项 | Janus | 本模块建议 | 备注 |
|---|---|---|---|
| `ConflictAction` | `resume / overwrite / skip / rename` | **保留** `resume`（语义：目标更大则续传） | Janus 多一个动作是真实价值 |
| `transfer:clear` 通道 | 有 | **保留**（`engine.clear()`） | UI 列表/设置里需要"清空全部" |
| `localfs:*` 通道 | 有 | **不放本模块**（宿主侧职责） | Janus 也只是 UI 导航用 |
| 符号链接策略 | upload 跟文件 / download 跳全部 | **沿用 Janus** | 文档化，避免循环 |
| 并发限流 | 3 写死 | 默认 3，构造时可选 | 给测试 / 主机调整 |
| 进度节流 | 写死 4Hz | 同样 4Hz（构造时可选 `emitMinIntervalMs?`）| 简单 |
| 断点续传粒度 | 字节 | 字节 | Janus 同 |
| 退避 | `[3k,5k,10k,20k,30k]`，最多 5 次 | 同上（构造时可选覆盖） | 同 |
| shutdown 行为 | 取消全部 + 清定时器 + 关 backend | **必须**如此 | Janus F5 修复点 |
| `destroyActive` 钩子 | 用 `t.destroyActive = () => fail(new Error('CANCELED'))` 主动关流 | **保留** | 防止 cancel 时抛 ECONNRESET |

### 3.3 依赖与构建
- `package.json`：
  - `name: "@kuikly/file-transfer"`
  - `type: "module"`，输出 `dist/`
  - `optionalDependencies: { ssh2: "^1.15.0" }`（**不**写为 dependencies——只在本机用 SFTP 的宿主会装；纯本地 / 测试宿主无需 ssh2）
  - `devDependencies: { typescript }`，`scripts: { build, typecheck, test }`
- `tsconfig.json`：`target ES2022`, `module ES2022`, `declaration: true`, `outDir dist`。
- 仓库 `electron/package.json` 用 `file:../packages/file-transfer` 引用。
- KuiklyUI 根 `package.json` **不**改 workspaces（保持改动最小；需要时再升级）。

---

## 4. 包与目录布局

```
KuiklyUI/
├─ packages/
│  └─ file-transfer/                 ← 独立 npm 包（待你确认后创建）
│     ├─ package.json                 name: "@kuikly/file-transfer", type: "module"
│     ├─ tsconfig.json
│     ├─ README.md
│     ├─ src/
│     │  ├─ types.ts
│     │  ├─ engine.ts                 TransferEngine 实现
│     │  ├─ index.ts                  createTransferEngine + 类型/类导出
│     │  └─ backends/
│     │     ├─ types.ts
│     │     ├─ sftp-backend.ts         默认（依赖 ssh2，optionalDependency）
│     │     └─ local-backend.ts       进程内两目录拷贝（测试 + 同主机用）
│     └─ test/
│        └─ engine.test.mjs            node:test 单测（≥10 例）
├─ devDocs/kuikly-file-transfer-module.md   ← 本计划
├─ electron/                         宿主（M2 接入）
├─ sftp-gateway/                     正交
└─ ...
```

---

## 5. **如何开发**（分步骤 + 具体文件/代码骨架 + 验证）

> 每步有「做什么 / 产物 / 验证」三段。M4 之后才动 KuiklyUI。

### M1 — 模块骨架 + 本地后端 + 单测
**做什么**：
- 建 `packages/file-transfer/{package.json,tsconfig.json}`（如 §3.3）。
- `src/types.ts` 与 `src/backends/types.ts` 写 §3.1 的类型。
- `src/backends/local-backend.ts`：**两目录拷贝实现**（`createReadStream` + `createWriteStream`，`start: offset` 续传；`fs.rm` 覆盖；冲突走 `ConflictResolver`；符号链接不跟随；`close()` 释放）。可参考 §1.3 逻辑，但用 Node 内置 fs 实现（不引 ssh2）。
- `src/engine.ts`：`TransferEngine` 类，**完整实现** §1.1–1.5（队列 / pump / scan / copy / `destroyActive` / conflict 询问 / retry 退避 / `clear` / `shutdown` / 4Hz 节流 / `activePaths`）。`ConflictAction` 包含 `'resume'`。
- `src/index.ts`：导出 `createTransferEngine`、类、类型。
- `test/engine.test.mjs`：`node:test`，用例（≥10）：
  1. 单文件 upload 成功，progress 累加正确
  2. 单文件 download 成功
  3. 进度节流 4Hz（不超 `MAX_CONCURRENT` 进度事件）
  4. cancel 立即停止且 `destroyActive` 触发
  5. 文件夹递归：scan / mkdirp / 多文件
  6. 冲突：overwrite / rename / skip（`targetSize` 触发 `INTERRUPTED`/`resume` 决策）
  7. 重试：模拟 `INTERRUPTED` → 自动重试 5 次 → 第 6 次标记 `error`
  8. resume：把 `item.offset` 恢复到上次 `transfered`，重试成功
  9. clear + shutdown 不阻塞、关 session
  10. `activePaths` 反映 running/waiting-conflict 任务
  11. 符号链接：download 跳过；upload 仅跟文件
  12. 并发限流：`maxConcurrent=2` 时第三任务保持 queued
**产物**：`packages/file-transfer/` 可独立 `npm test` 通过（**不依赖** ssh2）。
**验证**：`cd packages/file-transfer && npm install && npm run typecheck && npm test` 全绿。

### M2 — SFTP 后端 + 与 KuiklyUI 集成
**做什么**：
- `src/backends/sftp-backend.ts`：用 `ssh2` 包，按 `SSHManager.getSftp` / `sftpStat` / `sftpMakedirs` 接口接入。`uploadFiles`/`downloadFiles` 用 `sftp.createReadStream` + `sftp.createWriteStream`（按 §1.3 写偏移、flags）。
- `electron/main.js`：
  - `npm install` 本地包（`file:../packages/file-transfer`）。
  - `const { createTransferEngine, SftpBackend } = require('@kuikly/file-transfer')` + `const ssh = …`
  - `const engine = createTransferEngine({ backend: new SftpBackend(ssh), emit: (ch, p) => win.webContents.send(ch, p), maxConcurrent: 3, chunkSize: 1 << 20 })`
  - 注册 `ipcMain.handle('transfer:start'/'cancel'/'resume'/'resolve'/'clear'/'list', …)` → 调 `engine.start/cancel/…`，将 `engine.onChange` 桥到 IPC 事件（`transfer:progress`/`transfer:conflict`/`transfer:status`）
  - `app.on('before-quit', () => engine.shutdown())`（同时关 gateway，**不可少**）
- `electron/preload.js`：`contextBridge.exposeInMainWorld('transfer', { start, cancel, resume, resolveConflict, clear, list, onProgress, onConflict, onStatus })`
**产物**：KuiklyUI Electron 桌面壳新增 `transfer:*` 桥接；运行时能在开发者工具或自定义 UI 发起传输。
**验证**：开发模式 `cd electron && npm run start`，选/拖一个本地文件到 SFTP 远端目录；或 `transfer:start` 走 RPC；`win.webContents.send('transfer:progress', …)` 收到。

### M3 — 回归 + 文档
**做什么**：
- 在 `devDocs/sftp-test-plan.md` 增一条人工用例：传输引擎冒烟（启动 electron → `transfer:start` → 校验 `progress` 事件流）。
- 跑 `bash scripts/run-all-tests.sh` 全量回归：L0/L1/L2/L3 仍 PASS。
**验证**：一键全量通过。

### M4（可选）— 渲染层 UI 接入
- KuiklyUI 若要做"文件管理"页：仅消费 preload 暴露的 `window.transfer.*`，不引本模块的 Node API。
- 范围扩张 — **等你决定**。

---

## 6. 测试策略
- **L1 单测**（独立，CI 必过）：`packages/file-transfer/test/engine.test.mjs`（`node:test`），覆盖 §5 M1 列出的 ≥10 例。
- **L2 集成**（KuiklyUI 内）：`electron/test/transfer.smoke.mjs`（可选，`run-all-tests.sh` 中 `SKIP_TRANSFER=1` 可跳过）。启动 electron + CDP，模拟一次 `transfer:start`，断言 0 异常。
- **L3 手动**：传输大文件 ≥100MB 校验 `bytesSent` 推进 + 取消后 `offset` 已记录；拉断网络触发重试退避。

---

## 7. 集成到 KuiklyUI electron 的步骤（与 §5 M2 对齐）
1. `cd electron && npm install file:../packages/file-transfer`（或在 `electron/package.json` 加 `dependencies` 后 `npm install`）。
2. `electron/main.js` 新增 `TransferEngine` 实例与 `transfer:*` 桥接（见 §5 M2）。
3. `electron/preload.js` 暴露 `window.transfer.*`。
4. 打包测试：`cd electron && npm run dist` 出 .dmg；**安装到 /Applications** 后跑 `npm run e2e`（或在 KuiklyUI 根）确认无回归。
5. 若 M4 启用 UI：按 §8 选定 UI 形态。

---

## 8. **待你确认 / 补充的功能清单**

### 8.1 M1 必做（建议采纳）
- [x] Janus 全部 transfer 语义（队列 / 断点 / 冲突 / 重试 / shutdown） ✅ 计划已含
- [x] `ConflictAction` 含 `'resume'` ✅
- [x] `clear` + `activePaths` ✅
- [x] 符号链接策略（upload 跟文件 / download 跳）✅
- [x] `destroyActive` 防止 cancel 抛 ECONNRESET ✅
- [x] 4Hz 进度节流 ✅
- [x] `SftpBackend` + `LocalBackend` ✅
- [x] 单测 ≥10 例 ✅

### 8.2 候选功能（**你可能想加的 — 请勾选/补充**）
- [ ] **`file://` 拖拽上传**（Janus F4，OS drag-drop 到 Remote 栏 → 走 transfer 引擎）— 范围：electron/渲染层；**不算**本模块内部
- [ ] **传输完成系统通知**（Janus F3）— 宿主侧
- [ ] **断线自动重连 + 传输自动恢复**（Janus F5，**`vaultLock` 调用 `engine.shutdown()`**）— 需在 M2 一并改 `vaultLock` 调 engine.shutdown
- [ ] **大文件 ≥2GB 性能测试**（边界）
- [ ] **压缩传输**（`zlib` 流式 pipe 上传/下载）— Janus 无
- [ ] **校验和**（上传完成后远端 `sftp stat` 比对 size/hash）
- [ ] **传输限速**（`throttle` 选项）
- [ ] **多 backend 并存**（一次任务到 SFTP、一次到 S3）
- [ ] **Manifest 持久化**（任务跨重启继续）— 需要 `persist` 抽象（已在 §3.1 设计）
- [ ] **事件订阅粒度**（按 channel / taskId 过滤；现在通过 `onChange` 统一回调，宿主在 emit 回调里按 payload 过滤即可，**未必需要** per-task 订阅）
- [ ] **并发任务** `engine.list()` 暴露 `TransferTask[]`（已有）+ 过滤
- [ ] **Dry-run 模式**（`startDryRun(req)`：只扫描 / 计算字节 / 列冲突，不实际传输）
- [ ] **Bandwidth throttling per task**
- [ ] **进度细分**（当前按字节；可加 per-file 速度 ETA）
- [ ] **Folder sync 双向**（Janus 显式不做；`practical-features` §「范围」第 4 行写"**排除**文件夹双向同步"） — 不做，**保持**
- [ ] **传输历史/审计**（`history.json`，由宿主提供 `persist`）
- [ ] **KuiklyUI 渲染层 UI**（M4 才做，**不是模块职责**）

### 8.3 其他需你拍板
- **传输事件频道前缀**（KuiklyUI 端用 `transfer:*` 与 Janus 一致？还是改 `kuiklyTransfer:*` 以避免与 Janus IPC 冲突） — 建议**沿用 `transfer:*`**（Janus 风格）
- **错误码**（Janus 走 SSHManager 的 `SftpErrorCode`；本模块建议抛普通 `Error` 携带 `code` 字段，由宿主翻译）
- **并发上限 / 退避默认值**（沿用 Janus：3 / `[3k,5k,10k,20k,30k]`，最多 5） — 建议**沿用**
- **本地 FS 范围**（本模块**不**含 `localfs:*`；宿主侧已有）— 建议**不含**
- **包发版**（`@kuikly/file-transfer` 发到 npm 还是仅 file: 引用）— 短期 `file:` 即可

---

## 9. 风险与缓解
| 风险 | 缓解 |
|---|---|
| 与 Janus 后续修改传染 | 本模块独立测试 + 独立版本；Janus 演进不影响本模块 |
| 大文件 OOM | Janus 已验证 1 MiB 分块流式；本模块同样默认 1 MiB；测试覆盖大文件 |
| Janus 端改了 IPC 事件名 | `emit(channel, payload)` 抽象，宿主映射；本模块不变 |
| Janus 端改了 ConflictAction 集合 | `ConflictAction` 联合类型 + `resume` 已含；**加新动作**只扩 enum，本模块跟着加 |
| Janus 端改了 retry 策略 | 构造时 `retryBackoffMs` / `retryMax` 可覆盖 |
| Janus 端改了节流 | 同上 `emitMinIntervalMs?` |
| 凭据泄露 | 模块不接密码/私钥；只接 `serverId`，宿主解析 |
| 取消/退出竞态 | `shutdown()` 幂等；`destroyActive` 立即关流避免 ECONNRESET |

---

## 10. 不变量（防回退）
- **I1** `packages/file-transfer` 不得 import `electron` / DOM / KuiklyUI 任何路径
- **I2** 后端通过 `TransportBackend` 抽象接入；模块不 `require('ssh2')` 在非 `backends/sftp-backend.ts` 路径
- **I3** 对外只暴露 §3.1 API；事件名以 `transfer:` 前缀通过 `emit` 透传，模块**不**耦合 KuiklyUI IPC 名（宿主可自行映射）
- **I4** KMP / commonRender 任何 `git diff --check` 不应出现 `file-transfer` 的 import
- **I5** `electron/` 通过 `file:../packages/file-transfer` 依赖；不复制源码
- **I6** 单测**不**依赖 ssh2（用 LocalBackend）；ssh2 在 `optionalDependencies`

---

## 11. 下一步（**等你决定**）
1. ✅ / ❌ 采纳 §3 方案（模块边界 + API + 默认值 + `ConflictAction` 含 `resume`）
2. 勾选 §8.2 候选功能（哪些加、哪些不做）
3. 确认 §8.3 拍板项（事件前缀 / 错误模型 / 本地 FS 范围 / 发版方式）
4. 批准后 → 我按 §5 顺序落地：M1 建包 + 本地后端 + 单测 → M2 集成到 electron → M3 回归



---

## 6. 已落地（Web / 桌面，2026-09 实测）

本节记录**已经跑通并自动化验证**的部分（不是计划），便于对照 §5 的 M1/M2 进度。

### 6.1 分层与文件

| 层 | 位置 | 说明 |
|----|------|------|
| 共享核心（纯状态机） | `core/file-manager/`（`jvm` + `js`） | `FileManagerModule`（路径归一/越界拦截、排序过滤、选中、操作计划、传输计划）、`PathPolicy`、`Types`；**无协程、无 IO、无后端接口** |
| 页面（Web/桌面） | `demo/src/jsMain/.../pages/sftp/FilesDualPanePage.kt` | 双栏 UI + 交互；逻辑全部委托核心 |
| 本地 FS（宿主） | `electron/main.js` `localfs:*` + `electron/preload.js` `window.localFs.*` | `home/list/stat/mkdir/rename/remove/readFile/writeFile`；`LOCAL_ROOT = app.getPath('home')`，越界拒绝 |
| 远端通道 | `SftpModule` + `sftp-gateway`（Node，`ssh2`） | 浏览器不能直连 SSH，经网关代持 |

核心单测：`./gradlew :core:file-manager:jvmTest :core:file-manager:jsNodeTest` → **76/76**。

### 6.2 入口（产品形态：先有远端，再有双栏）

1. **首页 → 默认「本地文件管理」**（Web/桌面专属行）：只开本地栏；远端栏显示「未连接远端 · 点标题 ⇄ 选择主机」。
2. **首页连接列表每行右侧「⇄」**：以该主机直接进双栏。
3. **浏览页（单栏）右上角「⇄」**：带当前远端目录进双栏（`remotePath` 作为远端栏初始位置）。
4. **页内远端栏标题「⇄」**：主机会话切换器（列已保存主机 + 「＋ 连接其他主机」）→ 解决「多个远端」。

### 6.3 交互语义（与 §0 的取舍）

- **活动栏**：工具条（新建/重命名/删除）作用于**活动栏**（最近点击过的那栏，标题 `●` 标记）。
  > 反例（已修）：早期工具条固定作用于本地栏 → 用户在远端选中后点删除会删掉**本地同名文件**（测试夹具就是这样被误删的）。
- **行点击 = 选中**（目录也可选，才能删除/重命名/整目录传输）；**目录行右侧 `▶` = 进入**。
- **目录优先排序**：目录恒定排在文件前，升降序只作用于同组（`FileManagerModule.applyView`）。
- 弹层：新建（空输入 + placeholder）/ 重命名（预填原名）/ 删除确认。
- 传输：`上传 →`（本地选中 → 远端栏 cwd）、`← 下载`（远端选中 → 本地栏 cwd），逐项顺序执行并回报状态。

### 6.4 网关为双栏做的两处扩展（仅绑回环，不改公共 API）

`SftpModule.upload/download` 原样可用：

- `upload`：无 `content` 时接受 `localPath`，由网关直接读本地文件（网关与调用方同机；仅 127.0.0.1）。
- `download`：`localName` 为**绝对路径**时直接写该路径（双栏「下载到本地栏」用）。

### 6.5 尚未落地（按 §5 继续）

目录递归传输（`isDir`）、字节偏移断点续传与重试退避、拖拽传输、远程编辑器、多远端同屏（三栏）、H5（非 Electron）本地栏（需 File System Access API）。
