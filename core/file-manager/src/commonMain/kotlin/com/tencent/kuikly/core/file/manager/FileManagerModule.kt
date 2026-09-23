package com.tencent.kuikly.core.file.manager

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 双栏文件管理器核心 —— **纯状态机**（KMP 公共层，框架无关）。
 *
 * 设计（2026-09 定稿）：
 * - **核心不做 IO**：不持有网络/SSH/文件系统句柄，不调用任何后端。
 * - **宿主负责异步 IO**：本地列表（Electron `localfs:*` / 平台 API）、远端列表（`SftpModule`）、
 *   传输（传输引擎）统统由宿主完成，再把结果喂给核心（[setEntries]/[setError]/[onTransfer*]）。
 * - 核心只做：状态、选区、路径校验、排序过滤、**操作计划**（生成目标路径/传输请求，交宿主执行）、
 *   冲突记忆、编辑器内容校验。
 *
 * 这样核心与「同步/异步」「哪个平台」完全解耦；各端只换宿主实现。
 * 平台分支：禁止 `Platform.isXxx`。
 */
class FileManagerModule(
    val config: FileManagerConfig = FileManagerConfig(),
    val localRoot: String,
    val remotePane: PaneSpec,
    val pathPolicy: PathPolicy = PathPolicy(localRoot),
    private val remotePathPolicy: PathPolicy = PathPolicy(remotePane.cwd),
    val conflictResolver: ConflictResolver = DefaultConflictResolver,
) {
    data class PaneSpec(val serverId: String, val cwd: String)

    private val _state = MutableStateFlow(
        FileManagerState(
            local = PaneState(cwd = localRoot),
            remote = PaneState(cwd = remotePane.cwd),
        )
    )
    val state: StateFlow<FileManagerState> = _state.asStateFlow()

    /** 未经过滤/排序的原始列表（用于 setFilter/setSort 就地重算）。 */
    private val rawEntries = mutableMapOf(Pane.Local to emptyList<BackendEntry>(), Pane.Remote to emptyList<BackendEntry>())

    private var closed = false

    // ==================== 宿主喂数据 ====================

    fun paneCwd(pane: Pane): String = if (pane == Pane.Local) _state.value.local.cwd else _state.value.remote.cwd
    fun serverId(): String = remotePane.serverId

    fun setLoading(pane: Pane, loading: Boolean) {
        update { it.copyPane(pane) { p -> p.copy(loading = loading) } }
    }

    /** 宿主列目录成功后调用。entries 为原始顺序（核心负责过滤/排序）。 */
    fun setEntries(pane: Pane, cwd: String, entries: List<BackendEntry>) {
        rawEntries[pane] = entries
        update { s ->
            val st = if (pane == Pane.Local) s.local else s.remote
            s.copyPane(pane) { p ->
                p.copy(cwd = cwd, entries = applyView(p, entries), loading = false, errorMsg = null)
            }
        }
    }

    fun setError(pane: Pane, message: String) {
        update { it.copyPane(pane) { p -> p.copy(loading = false, errorMsg = message) } }
    }

    // ==================== 导航（纯） ====================

    fun normalize(pane: Pane, path: String): String = policyFor(pane).normalize(path)

    /** 子项绝对路径（越界抛 [PathEscapeException]）。 */
    fun childPath(pane: Pane, name: String): String = policyFor(pane).normalize("${paneCwd(pane)}/$name")

    /** 上一级路径；已在 root 返回 null。 */
    fun upPath(pane: Pane): String? {
        val norm = policyFor(pane).normalize(paneCwd(pane))
        val root = policyFor(pane).root.trimEnd('/')
        if (norm == root) return null
        return norm.substringBeforeLast('/', "").ifEmpty { root }
    }

    /** 设定当前目录、清空选择、置 loading（宿主随后 list 并 [setEntries]）。 */
    fun navigateTo(pane: Pane, cwd: String) {
        policyFor(pane).normalize(cwd)
        rawEntries[pane] = emptyList()
        update { it.copyPane(pane) { p -> p.copy(cwd = cwd, entries = emptyList(), selection = emptySet(), loading = true, errorMsg = null) } }
    }

    /** 进入子目录 / 返回上一级。返回目标路径；".." 且已在 root 时返回 null。 */
    fun enter(pane: Pane, name: String): String? {
        val target = if (name == "..") upPath(pane) else childPath(pane, name)
        if (target != null) navigateTo(pane, target)
        return target
    }

    // ==================== 选择 / 视图 ====================

    fun setSelection(pane: Pane, names: Set<String>) {
        update { it.copyPane(pane) { p -> p.copy(selection = names) } }
    }

    fun toggleSelection(pane: Pane, name: String) {
        update { it.copyPane(pane) { p -> p.copy(selection = p.selection.toMutableSet().apply { if (!add(name)) remove(name) }) } }
    }

    fun setFilter(pane: Pane, text: String) {
        update { it.copyPane(pane) { p -> p.copy(filter = text, entries = applyView(p.copy(filter = text), rawEntries[pane].orEmpty())) } }
    }

    fun setSort(pane: Pane, key: SortKey, order: SortOrder) {
        update { it.copyPane(pane) { p -> p.copy(sort = key, order = order, entries = applyView(p.copy(sort = key, order = order), rawEntries[pane].orEmpty())) } }
    }

    private fun applyView(st: PaneState, raw: List<BackendEntry>): List<BackendEntry> {
        val filtered = if (st.filter.isEmpty()) raw else raw.filter { it.name.contains(st.filter, ignoreCase = true) }
        val keyCmp: Comparator<BackendEntry> = when (st.sort) {
            SortKey.Name -> compareBy({ it.name.lowercase() }, { it.name })
            SortKey.Size -> compareBy { it.size }
            SortKey.Mtime -> compareBy { it.mtime }
        }
        // 目录优先（文件管理器惯例：目录恒定排在文件前）；升降序只作用于同组内部
        val dirFirst = compareByDescending<BackendEntry> { it.type == EntryType.Dir }
        val ordered = if (st.order == SortOrder.Desc) keyCmp.reversed() else keyCmp
        return filtered.sortedWith(dirFirst.then(ordered))
    }

    // ==================== 操作计划（宿主执行） ====================

    /** 新建目录的绝对路径。 */
    fun planMkdir(pane: Pane, name: String): String = childPath(pane, name)

    /** 重命名的 (from, to) 绝对路径。 */
    fun planRename(pane: Pane, from: String, to: String): Pair<String, String> =
        childPath(pane, from) to childPath(pane, to)

    /** 删除项绝对路径列表；根目录被保护（抛 IllegalStateException）。递归由宿主执行。 */
    fun planRemove(pane: Pane, names: List<String>): List<String> {
        val rootPath = policyFor(pane).normalize(if (pane == Pane.Local) localRoot else remotePane.cwd)
        return names.map { n ->
            val p = childPath(pane, n)
            check(p != rootPath) { "refuse to remove root: $p" }
            p
        }
    }

    /**
     * 选区 → 传输请求列表（upupload/download）。
     * 记忆的冲突策略（同向、未过期）会置 `overwrite=true`。
     */
    fun planTransfer(items: List<FileItem>, direction: TransferDirection): List<TransferRequest> {
        check(items.isNotEmpty()) { "无选中项" }
        val localCwd = _state.value.local.cwd
        val remoteCwd = _state.value.remote.cwd
        val s = _state.value
        val remembered = s.rememberedActions[direction]
        val expired = s.rememberedExpiry[direction]?.let { nowMillis() > it } ?: true
        val overwrite = remembered != null && !expired
        return items.map { it ->
            val name = it.name.ifEmpty { it.remote.substringAfterLast('/').ifEmpty { it.remote.substringAfterLast('\\') } }
            val lp = if (localCwd.trimEnd('/').isEmpty()) name else "$localCwd/$name"
            val rp = if (remoteCwd.trimEnd('/').isEmpty()) name else "$remoteCwd/$name"
            TransferRequest(
                serverId = remotePane.serverId,
                direction = direction,
                localPath = policyFor(Pane.Local).normalize(lp),
                remotePath = policyFor(Pane.Remote).normalize(rp),
                isDir = it.isDir,
                overwrite = overwrite,
            )
        }
    }

    /** 编辑器保存计划：把编辑内容（宿主已写入 tempPath）上传回原远端路径。 */
    fun planEditorSave(session: EditorSession, localTempPath: String): TransferRequest = TransferRequest(
        serverId = remotePane.serverId,
        direction = TransferDirection.Upload,
        localPath = localTempPath,
        remotePath = session.path,
        overwrite = true,
    )

    /** 编辑器内容校验（内容由宿主读取）：禁用 / 超限 / 二进制（含 NUL）→ null。 */
    fun validateEditor(path: String, content: ByteArray): EditorSession? {
        if (!config.enableFileEditor) return null
        if (content.size > config.maxEditorBytes) return null
        if (content.any { it == 0.toByte() }) return null
        return EditorSession(content = content, path = path, encoding = "utf-8", isDirty = false)
    }

    // ==================== 传输状态（宿主喂引擎事件） ====================

    fun onTransferStarted(taskId: String, req: TransferRequest, bytesTotal: Long = 0L) {
        update { s ->
            s.copyTransfers(s.transfers + (taskId to TransferTask(
                id = taskId, serverId = req.serverId, direction = req.direction,
                name = req.localPath.substringAfterLast('/').ifEmpty { req.localPath },
                status = TransferStatus.Running, bytesTotal = bytesTotal, startedAt = nowMillis(),
            )))
        }
    }

    fun onTransferProgress(taskId: String, bytesSent: Long, bytesTotal: Long, currentFile: String? = null) {
        update { s ->
            val t = s.transfers[taskId] ?: return@update s
            val u = t.copy(bytesSent = bytesSent, bytesTotal = bytesTotal, currentFile = currentFile)
            val done = u.bytesTotal > 0 && u.bytesSent >= u.bytesTotal
            s.copyTransfers(s.transfers + (taskId to if (done) u.copy(status = TransferStatus.Done, finishedAt = nowMillis()) else u))
        }
    }

    fun onTransferStatus(taskId: String, status: TransferStatus, error: String? = null) {
        update { s ->
            val t = s.transfers[taskId] ?: TransferTask(
                id = taskId, serverId = remotePane.serverId, direction = TransferDirection.Upload,
                name = taskId, status = status, error = error, startedAt = nowMillis(),
            )
            s.copyTransfers(s.transfers + (taskId to t.copy(status = status, error = error, finishedAt = if (status.isTerminal()) nowMillis() else t.finishedAt)))
        }
    }

    fun clearTransfers() {
        update { it.copyTransfers(emptyMap()) }
    }

    // ==================== 冲突记忆 ====================

    fun rememberAction(direction: TransferDirection, action: ConflictAction, forMs: Long = 60_000L) {
        val exp = nowMillis() + forMs
        update {
            it.copy(
                rememberedActions = it.rememberedActions + (direction to action),
                rememberedExpiry = it.rememberedExpiry + (direction to exp),
            )
        }
    }

    /** 关闭：清空状态（幂等）。宿主自行负责引擎/会话的关闭。 */
    fun shutdown() {
        if (closed) return
        closed = true
        update { it.copy(transfers = emptyMap(), rememberedActions = emptyMap(), rememberedExpiry = emptyMap()) }
    }

    // ==================== 内部 ====================

    private fun policyFor(pane: Pane): PathPolicy = if (pane == Pane.Local) pathPolicy else remotePathPolicy

    private fun update(block: (FileManagerState) -> FileManagerState) {
        _state.value = block(_state.value)
    }

    private fun FileManagerState.copyPane(pane: Pane, block: (PaneState) -> PaneState): FileManagerState =
        if (pane == Pane.Local) copy(local = block(local)) else copy(remote = block(remote))

    private fun FileManagerState.copyTransfers(t: Map<String, TransferTask>) = copy(transfers = t)
}

private fun TransferStatus.isTerminal() = this == TransferStatus.Done || this == TransferStatus.Error || this == TransferStatus.Canceled

/** 默认冲突解决：相同尺寸 → Skip；否则 → Overwrite。 */
val DefaultConflictResolver: ConflictResolver = { info ->
    if (info.sizeRemote != null && info.sizeLocal == info.sizeRemote) ConflictActionAndName(ConflictAction.Skip)
    else ConflictActionAndName(ConflictAction.Overwrite)
}

enum class SortKey { Name, Size, Mtime }
enum class SortOrder { Asc, Desc }

data class PaneState(
    val cwd: String,
    val entries: List<BackendEntry> = emptyList(),
    val selection: Set<String> = emptySet(),
    val sort: SortKey = SortKey.Name,
    val order: SortOrder = SortOrder.Asc,
    val filter: String = "",
    val loading: Boolean = false,
    val errorMsg: String? = null,
)

data class FileManagerState(
    val local: PaneState,
    val remote: PaneState,
    val transfers: Map<String, TransferTask> = emptyMap(),
    val rememberedActions: Map<TransferDirection, ConflictAction> = emptyMap(),
    val rememberedExpiry: Map<TransferDirection, Long> = emptyMap(),
)
