/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://github.com/Tencent-TDS/KuiklyUI/blob/main/LICENSE
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tencent.kuikly.demo.pages.sftp

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.datetime.DateTime
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.file.manager.BackendEntry
import com.tencent.kuikly.core.file.manager.EntryType
import com.tencent.kuikly.core.file.manager.FileItem
import com.tencent.kuikly.core.file.manager.FileManagerModule
import com.tencent.kuikly.core.file.manager.Pane
import com.tencent.kuikly.core.file.manager.TransferDirection
import com.tencent.kuikly.core.file.manager.TransferRequest
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.sftp.OverwriteMode
import com.tencent.kuikly.core.module.sftp.SftpConnectParam
import com.tencent.kuikly.core.module.sftp.SftpConnection
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.base.BridgeModule
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

data class DualTransferTask(
    val id: String,
    val direction: TransferDirection,
    val name: String,
    val totalBytes: Long,
    val doneBytes: Long = 0L,
    val progress: Float = 0f,
    val rateBytesPerSecond: Long = 0L,
    val status: String = "排队中",
    val error: String? = null,
    val updatedAt: Long = 0L,
)

/**
 * 双栏文件管理器（桌面版：Electron / H5）。
 *
 * 左栏 = 本地文件（宿主 `window.localFs`，根 = 用户主目录），
 * 右栏 = 远端 SFTP（`SftpModule`）。
 *
 * 业务逻辑全部委托给共享核心 [FileManagerModule]（纯状态机）：
 * 路径归一化/越界拦截、排序过滤、选中、冲突计划都由核心决定；
 * 本页只负责渲染 + 把结果喂回核心 + 调平台 IO。
 */
@Page(SftpPageNames.FILES_DUAL_PANE)
internal class FilesDualPanePage : SftpBasePager() {

    // ---------------- 路由参数 ----------------
    private var connectParam: SftpConnectParam? = null
    private var connectionId: String by observable("")
    private var connectionLabel: String by observable("")
    private var remoteHome: String = "/"

    // ---------------- 业务状态 ----------------
    private var sessionId: String? = null
    private var fm: FileManagerModule? = null

    // ---------------- UI 状态（可观察，异步回调写入即可触发重渲染）----------------
    private var localCwd: String by observable("")
    private var remoteCwd: String by observable("")
    private var localList by observableList<BackendEntry>()
    private var remoteList by observableList<BackendEntry>()
    private var localSel by observableList<String>()
    private var remoteSel by observableList<String>()
    private var localLoading: Boolean by observable(true)
    private var remoteLoading: Boolean by observable(true)
    private var connecting: Boolean by observable(true)
    private var errorMsg: String? by observable(null)
    private var status: String by observable("")
    /** 双栏传输任务：下载/上传均显示，下载额外显示实时速率。 */
    private var transferTasks by observableList<DualTransferTask>()
    private var transferDetailsVisible: Boolean by observable(false)
    private var transferPollRef: String? = null
    private var transferPollToken: String? = null
    /** 活动栏：工具条（新建/重命名/删除）作用于它；避免「在远端选中、却删了本地同名文件」。 */
    private var activePane: Pane by observable(Pane.Local)
    /** 已保存的远端主机（切换器数据源） */
    private var connList by observableList<SftpConnection>()
    private var pickerOpen: Boolean by observable(false)
    /** 远端是否已连接（本地文件管理模式为 false：远端栏等待用户选主机） */
    private var remoteReady: Boolean by observable(false)
    /** 本地栏不可用时的提示（纯 H5 没有 window.localFs；仍可用远端栏） */
    private var localHint: String? by observable(null)
    /** 工具条「显示隐藏:关/开」：**默认关 = 不显示隐藏文件**；双栏同时生效。 */
    private var showHidden: Boolean by observable(false)
    private var initialRemotePath: String = ""

    // 弹层（新建/重命名/删除确认）
    private var dialogKind: String by observable("")
    private var dialogPane: Pane by observable(Pane.Local)
    private var dialogInput: String by observable("")

    override fun created() {
        super.created()
        val params = pageData.params
        connectParam = SftpConnectParam.fromJson(params)
        connectionId = params.optString("connectionId", "")
        connectionLabel = params.optString("connectionLabel", "")
        remoteHome = params.optString("remoteHome", "").ifEmpty { "/" }
        initialRemotePath = params.optString("remotePath", "")
        loadConnections()
        doConnect()
    }

    override fun pageWillDestroy() {
        transferPollRef?.let { clearTimeout(it) }
        transferPollRef = null
        transferPollToken = null
        super.pageWillDestroy()
    }

    // ==================== 连接 & 初始化 ====================

    private fun doConnect() {
        val inline = connectParam
        if (inline != null && inline.host.isNotEmpty()) {
            connectNow(inline)
            return
        }
        if (connectionId.isNotEmpty()) {
            // 只给了 connectionId：从连接库取回凭据（原生侧加密存储）
            connecting = true
            errorMsg = null
            sftpConnectionModule().get(connectionId) { conn, err ->
                if (conn == null) {
                    errorMsg = err?.msg ?: "连接不存在"
                    connecting = false
                    localLoading = false
                    remoteLoading = false
                } else {
                    connectionLabel = conn.label
                    connectNow(
                        SftpConnectParam(
                            host = conn.host,
                            port = conn.port,
                            user = conn.user,
                            password = conn.password,
                            privateKey = conn.privateKey,
                            passphrase = conn.passphrase,
                            authMethod = conn.authMethod,
                        ),
                    )
                }
            }
            return
        }
        // 未指定任何远端：进入「本地文件管理」模式（远端栏等待用户选择主机）
        errorMsg = null
        connecting = false
        initModule(null)
    }

    private fun connectNow(conn: SftpConnectParam, keepLocalCwd: String? = null) {
        connecting = true
        errorMsg = null
        if (connectionLabel.isEmpty()) connectionLabel = "${conn.user}@${conn.host}"

        sftpModule().connect(conn) { sid, err ->
            if (err != null || sid == null) {
                errorMsg = err?.msg ?: "连接失败"
                connecting = false
                localLoading = false
                remoteLoading = false
            } else {
                sessionId = sid
                remoteReady = true
                initModule(keepLocalCwd)
            }
        }
    }

    private fun loadConnections() {
        sftpConnectionModule().list { items, _ ->
            connList.clear()
            connList.addAll(items)
        }
    }

    /** 切换远端主机：断开当前会话 → 用新主机重连 → 远端栏回到根目录（本地栏位置保留）。 */
    private fun switchRemote(conn: SftpConnection) {
        pickerOpen = false
        val keepLocal = fm?.paneCwd(Pane.Local)
        val start = {
            connectionId = conn.id
            connectionLabel = conn.label
            remoteHome = "/"
            sessionId = null
            connectNow(
                SftpConnectParam(
                    host = conn.host,
                    port = conn.port,
                    user = conn.user,
                    password = conn.password,
                    privateKey = conn.privateKey,
                    passphrase = conn.passphrase,
                    authMethod = conn.authMethod,
                ),
                keepLocalCwd = keepLocal,
            )
        }
        val sid = sessionId
        sessionId = null
        remoteReady = false
        if (sid != null) sftpModule().disconnect(sid) { start() } else start()
    }

    private fun initModule(preserveLocalCwd: String? = null) {
        lfHome { home, err ->
            if (home == null) {
                // 没有本地栏宿主（例如纯浏览器 H5）：降级为「远端可用、本地栏给提示」，不整页报错
                localHint = err ?: "本地文件不可用"
                connecting = false
            }
            val remoteRoot = remoteHome
            val core = FileManagerModule(
                localRoot = home ?: "/",   // 本地栏不可用时不会被真正使用（宿主会拒绝）
                remotePane = FileManagerModule.PaneSpec(serverId = connectionId.ifEmpty { "sftp" }, cwd = remoteRoot),
            )
            fm = core
            // 重建核心时保留当前的隐藏文件开关（切主机/重连后两栏继续保持用户选择）
            core.setShowHidden(Pane.Local, showHidden)
            core.setShowHidden(Pane.Remote, showHidden)
            connecting = false
            if (!preserveLocalCwd.isNullOrEmpty()) {
                // 切换主机时保留本地栏位置（远端栏重置到新主机根目录）
                try { core.navigateTo(Pane.Local, preserveLocalCwd) } catch (_: Throwable) { }
            }
            if (sessionId != null) {
                if (initialRemotePath.isNotEmpty()) {
                    try { core.navigateTo(Pane.Remote, initialRemotePath) } catch (_: Throwable) { }
                    initialRemotePath = ""
                }
                listPane(Pane.Remote)
            } else {
                remoteLoading = false
            }
            if (home != null) listPane(Pane.Local) else localLoading = false
        }
    }

    // ==================== 列表 ====================

    private fun listPane(pane: Pane) {
        val core = fm ?: return
        if (pane == Pane.Local) {
            localLoading = true
            val requested = core.paneCwd(Pane.Local)
            lfList(requested) { entries, err ->
                localLoading = false
                if (entries == null) {
                    status = err ?: "本地读取失败"
                    return@lfList
                }
                // 目录已切换（用户快速点了「↑」/进入）：丢弃这次陈旧响应，避免「路径是新的、内容是旧的」
                if (core.paneCwd(Pane.Local) != requested) return@lfList
                core.setEntries(Pane.Local, requested, entries)
                syncEntries(Pane.Local)
            }
        } else {
            val sid = sessionId
            if (sid == null) {
                remoteLoading = false
                return
            }
            remoteLoading = true
            val dir = core.paneCwd(Pane.Remote).ifEmpty { "/" }
            sftpModule().list(sid, dir) { items, _, err ->
                remoteLoading = false
                if (err != null) {
                    status = err.msg
                } else if (core.paneCwd(Pane.Remote).ifEmpty { "/" } != dir) {
                    // 同上：目录已切换，丢弃陈旧响应
                } else {
                val mapped = items.map {
                    BackendEntry(
                        name = it.name,
                        type = when {
                            it.isSymlink -> EntryType.Symlink
                            it.isDir -> EntryType.Dir
                            else -> EntryType.File
                        },
                        size = it.size,
                        mtime = it.mtime,
                    )
                }
                core.setEntries(Pane.Remote, dir, mapped)
                syncEntries(Pane.Remote)
                }
            }
        }
    }

    private fun listBoth() {
        listPane(Pane.Local)
        listPane(Pane.Remote)
    }

    /** 只在目录内容变化时刷新列表（O(n) 重建）；选中变化不要走这里。 */
    private fun syncEntries(pane: Pane) {
        val st = fm?.state?.value ?: return
        if (pane == Pane.Local) {
            localCwd = st.local.cwd
            localList.clear(); localList.addAll(st.local.entries)
        } else {
            remoteCwd = st.remote.cwd
            remoteList.clear(); remoteList.addAll(st.remote.entries)
        }
        syncSelection(pane)
    }

    /** 只同步选中集合（点选高频，不应重建整栏行）。 */
    private fun syncSelection(pane: Pane) {
        val st = fm?.state?.value ?: return
        if (pane == Pane.Local) {
            localSel.clear(); localSel.addAll(st.local.selection)
        } else {
            remoteSel.clear(); remoteSel.addAll(st.remote.selection)
        }
    }

    // ==================== 交互 ====================

    /** 行点击：一律「选中」（目录也能选 → 可删除/重命名/整目录传输）。 */
    private fun onSelect(pane: Pane, entry: BackendEntry) {
        val core = fm ?: return
        activePane = pane
        core.toggleSelection(pane, entry.name)
        syncSelection(pane)
        val n = core.state.value.let { if (pane == Pane.Local) it.local else it.remote }.selection.size
        status = "${if (pane == Pane.Local) "本地" else "远端"}已选 $n 项"
    }

    /** 目录行右侧「▶」：进入下一级。 */
    private fun onOpen(pane: Pane, entry: BackendEntry) {
        val core = fm ?: return
        activePane = pane
        if (entry.type != EntryType.Dir) return
        core.enter(pane, entry.name)
        listPane(pane)
    }

    private fun goUp(pane: Pane) {
        val core = fm ?: return
        activePane = pane
        val up = core.upPath(pane) ?: run { status = "已到顶层"; return }
        core.navigateTo(pane, up)
        listPane(pane)
    }

    /**
     * 工具条：切换「是否显示隐藏文件」（双栏同时生效）。
     *
     * 核心做过滤（[FileManagerModule.setShowHidden]），**不重新拉取列表**，
     * 因此切换是即时的（本地/远端都无需 IO 往返）；被隐藏的条目也会被剔出选中集合。
     */
    private fun toggleShowHidden() {
        showHidden = !showHidden
        val core = fm
        if (core != null) {
            core.setShowHidden(Pane.Local, showHidden)
            core.setShowHidden(Pane.Remote, showHidden)
        }
        syncEntries(Pane.Local)
        syncEntries(Pane.Remote)
        status = if (showHidden) "已显示隐藏文件" else "已隐藏隐藏文件"
    }

    private fun openDialog(kind: String, pane: Pane) {
        val core = fm ?: return
        activePane = pane
        val sel = if (pane == Pane.Local) localSel else remoteSel
        when (kind) {
            "mkdir" -> dialogInput = ""
            "rename" -> {
                if (sel.size != 1) { status = "重命名需选中 1 项"; return }
                dialogInput = sel.first()
            }
            "delete" -> {
                if (sel.isEmpty()) { status = "请先选中要删除的项"; return }
            }
        }
        dialogKind = kind
        dialogPane = pane
    }

    private fun confirmDialog() {
        val kind = dialogKind
        val pane = dialogPane
        val core = fm ?: return
        dialogKind = ""
        when (kind) {
            "mkdir" -> {
                val name = dialogInput.trim()
                if (name.isEmpty()) return
                val path = core.planMkdir(pane, name)
                if (pane == Pane.Local) {
                    lfMkdir(path) { err -> status = err ?: "已新建 $name"; listPane(pane) }
                } else {
                    val sid = sessionId ?: run { status = "未连接远端，请先在远端栏选择主机"; return }
                    sftpModule().mkdir(sid, path) { _, err ->
                        status = err?.msg ?: "已新建 $name"; listPane(pane)
                    }
                }
            }
            "rename" -> {
                val to = dialogInput.trim()
                if (to.isEmpty()) return
                val sel = if (pane == Pane.Local) localSel.toList() else remoteSel.toList()
                val from = sel.firstOrNull() ?: return
                val pair = core.planRename(pane, from, to)
                if (pane == Pane.Local) {
                    lfRename(pair.first, pair.second) { err -> status = err ?: "已重命名为 $to"; listPane(pane) }
                } else {
                    val sid = sessionId ?: run { status = "未连接远端，请先在远端栏选择主机"; return }
                    sftpModule().rename(sid, pair.first, pair.second) { _, err ->
                        status = err?.msg ?: "已重命名为 $to"; listPane(pane)
                    }
                }
            }
            "delete" -> {
                val names = if (pane == Pane.Local) localSel.toList() else remoteSel.toList()
                val paths = core.planRemove(pane, names)
                deleteSeq(pane, paths, 0)
            }
        }
    }

    private fun deleteSeq(pane: Pane, paths: List<String>, idx: Int) {
        if (idx >= paths.size) {
            fm?.setSelection(pane, emptySet())
            syncSelection(pane)
            status = "已删除 ${paths.size} 项"
            listPane(pane)
            return
        }
        if (pane == Pane.Local) {
            lfRemove(paths[idx]) { err ->
                if (err != null) {
                    status = "删除失败：$err"
                    listPane(pane)
                } else {
                    deleteSeq(pane, paths, idx + 1)
                }
            }
        } else {
            val sid = sessionId ?: run { status = "未连接远端"; return }
            sftpModule().rm(sid, paths[idx], recursive = true) { _, err ->
                if (err != null) {
                    // 失败必须显式报错并停止（绝不伪报成功）
                    status = "删除失败：${err.msg}"
                    listPane(pane)
                } else {
                    deleteSeq(pane, paths, idx + 1)
                }
            }
        }
    }

    // ==================== 传输 ====================

    private fun transfer(direction: TransferDirection) {
        val core = fm ?: return
        if (sessionId == null) { status = "请先在远端栏标题选择主机（⇄）"; return }
        val srcPane = if (direction == TransferDirection.Upload) Pane.Local else Pane.Remote
        val sel = if (srcPane == Pane.Local) localSel.toList() else remoteSel.toList()
        if (sel.isEmpty()) { status = "请先在${if (srcPane == Pane.Local) "本地" else "远端"}栏选中文件"; return }
        val srcEntries = if (srcPane == Pane.Local) localList.toList() else remoteList.toList()
        val items = sel.mapNotNull { name ->
            val e = srcEntries.firstOrNull { it.name == name } ?: return@mapNotNull null
            if (e.type == EntryType.Dir) return@mapNotNull null
            FileItem(
                local = core.childPath(Pane.Local, name),
                remote = core.childPath(Pane.Remote, name),
                size = e.size,
                name = name,
            )
        }
        if (items.isEmpty()) { status = "目录暂不支持传输，请选择文件"; return }
        val reqs = core.planTransfer(items, direction)
        transferTasks.clear()
        reqs.forEachIndexed { index, req ->
            val name = req.localPath.substringAfterLast('/').ifEmpty { req.remotePath.substringAfterLast('/') }
            val size = items.firstOrNull { it.name == name }?.size ?: 0L
            transferTasks.add(
                DualTransferTask(
                    id = "transfer_${DateTime.currentTimestamp()}_$index",
                    direction = direction,
                    name = name,
                    totalBytes = size,
                    updatedAt = DateTime.currentTimestamp(),
                ),
            )
        }
        transferDetailsVisible = true
        execSeq(direction, reqs, 0)
    }

    private fun transferDetailsTitle(): String {
        val directions = transferTasks.map { it.direction }.toSet()
        return when {
            directions.isEmpty() -> "传输详情"
            directions.size > 1 -> "传输详情"
            directions.first() == TransferDirection.Download -> "下载详情"
            else -> "上传详情"
        }
    }

    private fun startTransferPolling(index: Int, localPath: String) {
        stopTransferPolling()
        val token = "poll_${DateTime.currentTimestamp()}_$index"
        transferPollToken = token

        fun schedule() {
            transferPollRef = setTimeout(300) {
                if (transferPollToken != token) return@setTimeout
                val parent = localPath.substringBeforeLast('/').ifEmpty { "/" }
                val name = localPath.substringAfterLast('/')
                localFsBridge().lfList(parent) { entries, _ ->
                    if (transferPollToken != token) return@lfList
                    val size = entries?.firstOrNull { it.optString("name") == name }?.optLong("size", -1L)?.takeIf { it >= 0L }
                    val total = transferTasks.getOrNull(index)?.totalBytes ?: 0L
                    if (size != null && total > 0L) {
                        updateProgress(index, (size.toFloat() / total.toFloat()).coerceIn(0f, 0.999f), null)
                    }
                    if (transferTasks.getOrNull(index)?.status == "进行中") schedule()
                }
            }
        }
        schedule()
    }

    private fun stopTransferPolling() {
        transferPollRef?.let { clearTimeout(it) }
        transferPollRef = null
        transferPollToken = null
    }

    private fun updateProgress(
        index: Int,
        progress: Float,
        error: String?
    ) {
        val now = DateTime.currentTimestamp()
        val old = transferTasks.getOrNull(index) ?: return
        val p = progress.coerceIn(0f, 1f)
        val done = if (old.totalBytes > 0L) (old.totalBytes * p).toLong() else old.doneBytes
        val elapsed = now - old.updatedAt
        val rate = if (elapsed > 0L && done > old.doneBytes) {
            (done - old.doneBytes) * 1000L / elapsed
        } else old.rateBytesPerSecond
        transferTasks[index] = old.copy(
            doneBytes = done,
            progress = p,
            rateBytesPerSecond = rate,
            status = when {
                error != null -> "失败"
                p >= 1f -> "已完成"
                else -> "进行中"
            },
            error = error,
            updatedAt = now,
        )
    }

    private fun execSeq(direction: TransferDirection, reqs: List<TransferRequest>, idx: Int) {
        if (idx >= reqs.size) {
            status = "传输完成 ${reqs.size} 项"
            listBoth()
            return
        }
        val sid = sessionId ?: return
        val req = reqs[idx]
        // 计划里的冲突记忆（overwrite）必须真的下发，否则核心的冲突策略形同虚设
        val overwrite = if (req.overwrite) OverwriteMode.OVERWRITE else OverwriteMode.SKIP
        val label = "${if (direction == TransferDirection.Upload) "上传" else "下载"} ${idx + 1}/${reqs.size} ${req.localPath.substringAfterLast('/')}"
        status = "$label …"
        if (direction == TransferDirection.Upload) {
            sftpModule().upload(sid, req.localPath, req.remotePath, overwrite = overwrite) { p, _, err ->
                updateProgress(idx, p, err?.msg)
                status = if (err != null) "$label 失败: ${err.msg}" else "$label ${(p * 100).toInt()}%"
                if (err != null) { status = "$label 失败"; listBoth() } else execSeq(direction, reqs, idx + 1)
            }
        } else {
            // 下载：网关/宿主把远端文件写到该本地绝对路径（localName 传绝对路径）
            startTransferPolling(idx, req.localPath)
            sftpModule().download(sid, req.remotePath, req.localPath, overwrite = overwrite) { p, _, err ->
                stopTransferPolling()
                updateProgress(idx, p, err?.msg)
                status = if (err != null) "$label 失败: ${err.msg}" else "$label ${(p * 100).toInt()}%"
                if (err != null) { status = "$label 失败"; listBoth() } else execSeq(direction, reqs, idx + 1)
            }
        }
    }

    // ==================== 渲染 ====================

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    size(pagerData.pageViewWidth, pagerData.pageViewHeight)
                    paddingTop(pagerData.statusBarHeight)
                    backgroundColor(SftpColorTokens.bg)
                    flexDirectionColumn()
                }

                // 导航栏
                View {
                    attr {
                        size(pagerData.pageViewWidth, 48f)
                        flexDirectionRow(); alignItemsCenter()
                        padding(12f, 8f, 12f, 8f)
                        backgroundColor(SftpColorTokens.cardBg)
                    }
                    Text {
                        attr { text("<"); fontSize(22f); color(SftpColorTokens.textPrimary); size(32f, 32f) }
                        event { click { ctx.onBack() } }
                    }
                    Text {
                        attr {
                            text("文件管理")
                            fontSize(16f); color(SftpColorTokens.textPrimary)
                            flex(1f); marginLeft(6f)
                        }
                    }
                    Text {
                        attr { text("刷新"); fontSize(14f); color(SftpColorTokens.primary); marginLeft(8f) }
                        event { click { ctx.listBoth(); ctx.status = "已刷新" } }
                    }
                }

                // 连接中 / 错误
                vif({ ctx.connecting }) {
                    View {
                        attr { flex(1f); allCenter() }
                        Text { attr { text("连接中…"); fontSize(14f); color(SftpColorTokens.textSecondary) } }
                    }
                }
                velseif({ ctx.errorMsg != null }) {
                    View {
                        attr { flex(1f); allCenter() }
                        Text { attr { text(ctx.errorMsg ?: ""); fontSize(14f); color(SftpColorTokens.danger) } }
                    }
                }
                velse {
                    // 双栏
                    View {
                        attr { flex(1f); flexDirectionRow(); alignItemsStretch() }
                        DualPane(
                            titleProvider = { "本地" },
                            activeProvider = { ctx.activePane == Pane.Local },
                            cwdProvider = { ctx.localCwd },
                            entriesProvider = { ctx.localList },
                            selectedProvider = { ctx.localSel },
                            loadingProvider = { ctx.localLoading },
                            emptyHintProvider = { ctx.localHint },
                            onSelect = { ctx.onSelect(Pane.Local, it) },
                            onOpen = { ctx.onOpen(Pane.Local, it) },
                            onUp = { ctx.goUp(Pane.Local) },
                            onMkdir = { ctx.openDialog("mkdir", Pane.Local) },
                        )
                        View { attr { width(1f); backgroundColor(SftpColorTokens.divider) } }
                        DualPane(
                            titleProvider = { "远端 · " + (ctx.connectionLabel.ifEmpty { "未连接" }) },
                            activeProvider = { ctx.activePane == Pane.Remote },
                            cwdProvider = { ctx.remoteCwd },
                            entriesProvider = { ctx.remoteList },
                            selectedProvider = { ctx.remoteSel },
                            loadingProvider = { ctx.remoteLoading },
                            onSelect = { ctx.onSelect(Pane.Remote, it) },
                            onOpen = { ctx.onOpen(Pane.Remote, it) },
                            onUp = { ctx.goUp(Pane.Remote) },
                            onMkdir = { ctx.openDialog("mkdir", Pane.Remote) },
                            onTitleClick = { ctx.pickerOpen = true },
                            emptyHintProvider = { if (ctx.remoteReady) null else "未连接远端\n点标题「⇄」选择主机" },
                        )
                    }

                    // 工具条
                    View {
                        attr {
                            size(pagerData.pageViewWidth, 48f)
                            flexDirectionRow(); alignItemsCenter()
                            padding(8f, 4f, 8f, 4f)
                            backgroundColor(SftpColorTokens.cardBg)
                        }
                        // 工具条作用于「活动栏」（点过的那一栏）；活动栏标题高亮
                        DualBtn("新建") { ctx.openDialog("mkdir", ctx.activePane) }
                        DualBtn("重命名") { ctx.openDialog("rename", ctx.activePane) }
                        DualBtn("删除") { ctx.openDialog("delete", ctx.activePane) }
                        DualBtn("上传 →") { ctx.transfer(TransferDirection.Upload) }
                        DualBtn("← 下载") { ctx.transfer(TransferDirection.Download) }
                        // 隐藏文件开关（标签反映**当前状态**，对齐阅读器的「换行/不换行」约定）：
                        // 默认「显示隐藏:关」= 不显示隐藏文件；点一下显示，再点一下隐藏
                        DualBtn({ if (ctx.showHidden) "显示隐藏:开" else "显示隐藏:关" }) { ctx.toggleShowHidden() }
                    }
                }

                // 状态行
                View {
                    attr {
                        size(pagerData.pageViewWidth, 26f)
                        padding(10f, 0f, 10f, 0f)
                        backgroundColor(SftpColorTokens.cardBg)
                    }
                    Text { attr { text(ctx.status); fontSize(12f); color(SftpColorTokens.textSecondary) } }
                }

                // 传输状态栏：点击可展开下载/上传详情
                View {
                    attr {
                        size(pagerData.pageViewWidth, 42f)
                        padding(8f, 10f, 8f, 10f)
                        backgroundColor(SftpColorTokens.cardBg)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    event { click { if (ctx.transferTasks.isNotEmpty()) ctx.transferDetailsVisible = true } }
                    Text {
                        attr {
                            text(ctx.transferSummary())
                            fontSize(12f)
                            color(SftpColorTokens.textSecondary)
                            flex(1f)
                            lines(1)
                        }
                    }
                    if (ctx.transferTasks.isNotEmpty()) {
                        Text { attr { text(ctx.transferDetailsTitle() + " ›"); fontSize(12f); color(SftpColorTokens.primary); marginLeft(8f) } }
                    }
                }

                // 弹层：传输详情（下载进度、实时速率、错误信息）
                vif({ ctx.transferDetailsVisible }) {
                    View {
                        attr {
                            positionAbsolute(); left(0f); top(0f)
                            size(pagerData.pageViewWidth, pagerData.pageViewHeight)
                            backgroundColor(Color(0x66000000)); zIndex(100); allCenter()
                        }
                        View {
                            attr {
                                size(pagerData.pageViewWidth - 48f, pagerData.pageViewHeight * 0.58f)
                                backgroundColor(SftpColorTokens.bg)
                                borderRadius(12f)
                                padding(16f, 14f, 16f, 14f)
                                flexDirectionColumn()
                            }
                            View {
                                attr { flexDirectionRow(); alignItemsCenter() }
                                Text { attr { text(ctx.transferDetailsTitle()); fontSize(15f); fontWeightBold(); color(SftpColorTokens.textPrimary); flex(1f) } }
                                Text {
                                    attr { text("关闭"); fontSize(13f); color(SftpColorTokens.textSecondary) }
                                    event { click { ctx.transferDetailsVisible = false } }
                                }
                            }
                            Scroller {
                                attr { flex(1f); marginTop(10f); flexDirectionColumn(); showScrollerIndicator(true) }
                                vfor({ ctx.transferTasks }) { task ->
                                    View {
                                        attr {
                                            width(pagerData.pageViewWidth - 76f)
                                            padding(10f, 10f, 10f, 10f)
                                            marginBottom(8f)
                                            backgroundColor(SftpColorTokens.cardBg)
                                            borderRadius(8f)
                                            flexDirectionColumn()
                                        }
                                        View {
                                            attr { flexDirectionRow(); alignItemsCenter() }
                                            Text { attr { text(if (task.direction == TransferDirection.Download) "下载" else "上传"); fontSize(12f); color(SftpColorTokens.primary); marginRight(6f) } }
                                            Text { attr { text(task.name); fontSize(13f); color(SftpColorTokens.textPrimary); flex(1f); lines(1) } }
                                            Text { attr { text(task.status); fontSize(12f); color(if (task.error == null) SftpColorTokens.textSecondary else SftpColorTokens.danger) } }
                                        }
                                        Text {
                                            attr {
                                                text("${formatTransferSize(task.doneBytes)} / ${formatTransferSize(task.totalBytes)} · ${(task.progress * 100).toInt()}%" + if (task.rateBytesPerSecond > 0L) " · ${formatTransferRate(task.rateBytesPerSecond)}" else "")
                                                fontSize(11f); color(SftpColorTokens.textSecondary); marginTop(5f)
                                            }
                                        }
                                        View {
                                            attr { width(pagerData.pageViewWidth - 96f); height(5f); marginTop(7f); backgroundColor(SftpColorTokens.divider); borderRadius(3f) }
                                            View { attr { width((pagerData.pageViewWidth - 96f) * task.progress); height(5f); backgroundColor(SftpColorTokens.primary); borderRadius(3f) } }
                                        }
                                        if (task.error != null) {
                                            Text { attr { text(task.error ?: ""); fontSize(11f); color(SftpColorTokens.danger); marginTop(5f) } }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 弹层：切换远端主机（多个远端在此切换）
                vif({ ctx.pickerOpen }) {
                    View {
                        attr {
                            size(pagerData.pageViewWidth, pagerData.pageViewHeight)
                            backgroundColor(Color(0x66000000)); allCenter()
                        }
                        View {
                            attr {
                                size(pagerData.pageViewWidth - 64f, 340f)
                                backgroundColor(SftpColorTokens.cardBg)
                                borderRadius(12f)
                                padding(16f, 16f, 16f, 16f)
                                flexDirectionColumn()
                            }
                            Text { attr { text("切换远端主机"); fontSize(15f); color(SftpColorTokens.textPrimary) } }
                            Scroller {
                                attr { flex(1f); marginTop(8f); flexDirectionColumn() }
                                vfor({ ctx.connList }) { c ->
                                    View {
                                        attr {
                                            padding(10f, 8f, 10f, 8f)
                                            flexDirectionRow(); alignItemsCenter()
                                            backgroundColor(if (c.id == ctx.connectionId) Color(0x22007AFF) else SftpColorTokens.cardBg)
                                        }
                                        event { click { ctx.switchRemote(c) } }
                                        Text {
                                            attr {
                                                text("🖥 " + c.label)
                                                fontSize(13f); color(SftpColorTokens.textPrimary); flex(1f)
                                            }
                                        }
                                        Text { attr { text(c.user + "@" + c.host); fontSize(11f); color(SftpColorTokens.textSecondary) } }
                                    }
                                }
                            }
                            View { attr { flexDirectionRow(); alignItemsCenter(); marginTop(6f) } }
                            View {
                                attr { flexDirectionRow(); alignItemsCenter() }
                                Text {
                                    attr { text("取消"); fontSize(14f); color(SftpColorTokens.textSecondary); margin(8f, 8f, 8f, 0f) }
                                    event { click { ctx.pickerOpen = false } }
                                }
                                View { attr { flex(1f) } }
                                Text {
                                    attr { text("＋ 连接其他主机"); fontSize(14f); color(SftpColorTokens.primary); margin(8f, 0f, 8f, 8f) }
                                    event {
                                        click {
                                            ctx.pickerOpen = false
                                            ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                                                .openPage(SftpConnectEditPage.PAGE_NAME)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 弹层：新建 / 重命名 / 删除确认
                vif({ ctx.dialogKind.isNotEmpty() }) {
                    View {
                        attr {
                            size(pagerData.pageViewWidth, pagerData.pageViewHeight)
                            backgroundColor(Color(0x66000000)); allCenter()
                        }
                        View {
                            attr {
                                size(pagerData.pageViewWidth - 64f, 168f)
                                backgroundColor(SftpColorTokens.cardBg)
                                borderRadius(12f)
                                padding(16f, 16f, 16f, 16f)
                                flexDirectionColumn()
                            }
                            Text {
                                attr {
                                    text(
                                        when (ctx.dialogKind) {
                                            "mkdir" -> "新建文件夹（${if (ctx.dialogPane == Pane.Local) "本地" else "远端"}）"
                                            "rename" -> "重命名"
                                            else -> "确认删除选中的项？"
                                        }
                                    )
                                    fontSize(15f); color(SftpColorTokens.textPrimary)
                                }
                            }
                            vif({ ctx.dialogKind != "delete" }) {
                                View {
                                    attr {
                                        size(pagerData.pageViewWidth - 96f, 40f)
                                        marginTop(12f)
                                        backgroundColor(SftpColorTokens.bg)
                                        borderRadius(8f)
                                        padding(10f, 8f, 10f, 8f)
                                    }
                                    Input {
                                        attr {
                                            placeholder("名称")
                                            text(ctx.dialogInput)
                                            fontSize(14f)
                                            color(SftpColorTokens.textPrimary)
                                            height(24f); flex(1f)
                                        }
                                        event { textDidChange { s -> ctx.dialogInput = s.text } }
                                    }
                                }
                            }
                            View {
                                attr { flex(1f) }
                            }
                            View {
                                attr { flexDirectionRow(); alignItemsCenter() }
                                Text {
                                    attr { text("取消"); fontSize(14f); color(SftpColorTokens.textSecondary); margin(8f, 8f, 8f, 16f) }
                                    event { click { ctx.dialogKind = "" } }
                                }
                                View { attr { flex(1f) } }
                                Text {
                                    attr {
                                        text(if (ctx.dialogKind == "delete") "删除" else "确定")
                                        fontSize(14f)
                                        color(if (ctx.dialogKind == "delete") SftpColorTokens.danger else SftpColorTokens.primary)
                                        margin(8f, 8f, 8f, 8f)
                                    }
                                    event { click { ctx.confirmDialog() } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun transferSummary(): String {
        if (transferTasks.isEmpty()) return status
        val active = transferTasks.count { it.status == "进行中" || it.status == "排队中" }
        val finished = transferTasks.count { it.status == "已完成" }
        val failed = transferTasks.count { it.status == "失败" }
        val current = transferTasks.firstOrNull { it.status == "进行中" }
        val currentText = current?.let {
            "${it.name} ${(it.progress * 100).toInt()}%" + if (it.rateBytesPerSecond > 0L) " · ${formatTransferRate(it.rateBytesPerSecond)}" else ""
        }
        return (currentText ?: "传输任务") + " · 进行中 $active · 完成 $finished" + if (failed > 0) " · 失败 $failed" else ""
    }

    private fun onBack() {
        val sid = sessionId
        val router = acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        if (sid != null) {
            sftpModule().disconnect(sid) { router.closePage() }
        } else {
            router.closePage()
        }
    }

    companion object {
        const val PAGE_NAME = SftpPageNames.FILES_DUAL_PANE
    }
}

// ==================== 单栏渲染 ====================

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.DualPane(
    titleProvider: () -> String,
    activeProvider: () -> Boolean,
    cwdProvider: () -> String,
    entriesProvider: () -> ObservableList<BackendEntry>,
    selectedProvider: () -> ObservableList<String>,
    loadingProvider: () -> Boolean,
    onSelect: (BackendEntry) -> Unit,
    onOpen: (BackendEntry) -> Unit,
    onUp: () -> Unit,
    onMkdir: () -> Unit,
    onTitleClick: (() -> Unit)? = null,
    emptyHintProvider: () -> String? = { null },
) {
    View {
        attr { width((pagerData.pageViewWidth - 1f) / 2f); flexDirectionColumn(); backgroundColor(SftpColorTokens.bg) }

        // 栏头：标题 + 当前路径 + 上级
        View {
            attr {
                flexDirectionRow(); alignItemsCenter()
                padding(8f, 6f, 8f, 6f)
                backgroundColor(SftpColorTokens.cardBg)
            }
            Text {
                attr {
                    // 活动栏标记（工具条作用于它）：attr 内读 provider 才会随点击重渲染；
                    // 远端栏标题可点 → 打开「切换主机」列表（多个远端靠它切换）
                    text((if (activeProvider()) "● " else "○ ") + titleProvider() + if (onTitleClick != null) " ⇄" else "")
                    fontSize(12f)
                    color(if (activeProvider()) SftpColorTokens.primary else SftpColorTokens.textSecondary)
                }
                if (onTitleClick != null) {
                    event { click { onTitleClick() } }
                }
            }
            Text {
                attr {
                    // 必须在 attr{} 内读 provider：Kuikly 只在这里收集响应式依赖
                    text(cwdProvider().ifEmpty { "…" })
                    fontSize(11f); color(SftpColorTokens.textSecondary)
                    flex(1f); marginLeft(6f)
                    lines(1)
                }
            }
            Text {
                attr { text("↑"); fontSize(14f); color(SftpColorTokens.textPrimary); marginLeft(6f) }
                event { click { onUp() } }
            }
            Text {
                attr { text("+"); fontSize(16f); color(SftpColorTokens.primary); marginLeft(8f) }
                event { click { onMkdir() } }
            }
        }

        vif({ loadingProvider() }) {
            View {
                attr { flex(1f); allCenter() }
                Text { attr { text("加载中…"); fontSize(12f); color(SftpColorTokens.textSecondary) } }
            }
        }
        velseif({ entriesProvider().isEmpty() && emptyHintProvider() != null }) {
            View {
                attr { flex(1f); allCenter(); padding(12f, 12f, 12f, 12f) }
                Text { attr { text(emptyHintProvider() ?: ""); fontSize(12f); color(SftpColorTokens.textSecondary) } }
            }
        }
        velse {
            Scroller {
                attr {
                    flex(1f)
                    width((pagerData.pageViewWidth - 1f) / 2f)
                    showScrollerIndicator(true)
                    flexDirectionColumn()
                    backgroundColor(SftpColorTokens.bg)
                }
                vfor(entriesProvider) { entry ->
                    View {
                        attr {
                            width((pagerData.pageViewWidth - 1f) / 2f - 8f)   // 预留滚动条宽度，避免大小列被裁
                            padding(10f, 8f, 10f, 8f)
                            flexDirectionRow(); alignItemsCenter()
                            backgroundColor(if (selectedProvider().contains(entry.name)) Color(0x33007AFF) else SftpColorTokens.cardBg)
                        }
                        event { click { onSelect(entry) } }
                        Text {
                            attr {
                                text(if (entry.type == EntryType.Dir) "📁 " else "📄 ")
                                fontSize(14f)
                            }
                        }
                        Text {
                            attr {
                                text(entry.name)
                                fontSize(13f); color(SftpColorTokens.textPrimary)
                                flex(1f)
                                lines(1)
                            }
                        }
                        if (entry.type == EntryType.Dir) {
                            // 目录：右侧「▶」进入下一级（行点击只选中，便于删除/重命名/整目录传输）
                            Text {
                                attr { text("▶"); fontSize(12f); color(SftpColorTokens.primary); marginLeft(6f) }
                                event { click { onOpen(entry) } }
                            }
                        } else {
                            Text {
                                attr { text(fmtSize(entry.size)); fontSize(11f); color(SftpColorTokens.textSecondary) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.DualBtn(label: String, onClick: () -> Unit) {
    Text {
        attr {
            text(label)
            fontSize(13f)
            color(SftpColorTokens.textPrimary)
            margin(8f, 6f, 8f, 6f)
        }
        event { click { onClick() } }
    }
}

/**
 * 动态标签版（用于开关类按钮：「显示隐藏:关/开」）。
 *
 * 标签必须在 `attr {}` 内求值，否则 observable 依赖收集不到 → 点了不刷新标签（AGENTS §13.4 第 1 条）。
 */
private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.DualBtn(labelProvider: () -> String, onClick: () -> Unit) {
    Text {
        attr {
            text(labelProvider())
            fontSize(13f)
            color(SftpColorTokens.textPrimary)
            margin(8f, 6f, 8f, 6f)
        }
        event { click { onClick() } }
    }
}

private fun formatTransferSize(bytes: Long): String = when {
    bytes < 1024L -> "${bytes}B"
    bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
    bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)}MB"
    else -> "${bytes / (1024L * 1024L * 1024L)}GB"
}

private fun formatTransferRate(bytesPerSecond: Long): String = when {
    bytesPerSecond <= 0L -> "0B/s"
    bytesPerSecond < 1024L -> "${bytesPerSecond}B/s"
    bytesPerSecond < 1024L * 1024L -> "${bytesPerSecond / 1024L}KB/s"
    bytesPerSecond < 1024L * 1024L * 1024L -> "${bytesPerSecond / (1024L * 1024L)}MB/s"
    else -> "${bytesPerSecond / (1024L * 1024L * 1024L)}GB/s"
}

private fun fmtSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}K"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}M"
    else -> "${bytes / (1024 * 1024 * 1024)}G"
}


private fun mapEntryType(t: String): EntryType = when (t) {
    "dir" -> EntryType.Dir
    "symlink" -> EntryType.Symlink
    "file" -> EntryType.File
    else -> EntryType.Other
}

// ==================== 本地文件系统（跨端 BridgeModule 能力）====================
// Web/桌面：宿主 window.localFs；Android/iOS/macOS/OHOS：各端沙盒本地文件实现；
// MiniApp：supportsLocalFs=false → 本地栏不可用（远端栏仍可用）。

internal fun FilesDualPanePage.localFsBridge(): BridgeModule =
    acquireModule(BridgeModule.MODULE_NAME)

internal fun FilesDualPanePage.lfHome(cb: (String?, String?) -> Unit) {
    localFsBridge().lfHome(cb)
}

internal fun FilesDualPanePage.lfList(dir: String, cb: (List<BackendEntry>?, String?) -> Unit) {
    localFsBridge().lfList(dir) { entries, err ->
        if (entries == null) {
            cb(null, err)
            return@lfList
        }
        val out = ArrayList<BackendEntry>(entries.size)
        for (e in entries) {
            out.add(
                BackendEntry(
                    name = e.optString("name"),
                    type = mapEntryType(e.optString("type")),
                    size = e.optLong("size", 0L),
                    mtime = e.optLong("mtime", 0L),
                ),
            )
        }
        cb(out, null)
    }
}

internal fun FilesDualPanePage.lfMkdir(path: String, cb: (String?) -> Unit) {
    localFsBridge().lfMkdir(path, cb)
}

internal fun FilesDualPanePage.lfRename(from: String, to: String, cb: (String?) -> Unit) {
    localFsBridge().lfRename(from, to, cb)
}

internal fun FilesDualPanePage.lfRemove(path: String, cb: (String?) -> Unit) {
    localFsBridge().lfRemove(path, cb)
}
