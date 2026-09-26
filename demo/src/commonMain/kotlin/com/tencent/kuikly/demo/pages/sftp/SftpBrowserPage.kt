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
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.module.sftp.MimeExtMap
import com.tencent.kuikly.core.module.sftp.SftpConnectParam
import com.tencent.kuikly.core.module.sftp.SftpConnection
import com.tencent.kuikly.core.module.sftp.SftpEntry
import com.tencent.kuikly.core.module.sftp.SftpError
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.base.BridgeModule
import com.tencent.kuikly.demo.pages.base.Utils
import com.tencent.kuikly.demo.pages.sftp.cache.CacheEngine
import com.tencent.kuikly.demo.pages.sftp.cache.CacheFile
import com.tencent.kuikly.demo.pages.sftp.cache.CacheIo
import com.tencent.kuikly.demo.pages.sftp.cache.CacheManager
import com.tencent.kuikly.demo.pages.sftp.cache.CacheTask
import com.tencent.kuikly.demo.pages.sftp.cache.CacheTasksOverlay
import com.tencent.kuikly.demo.pages.sftp.theme.SftpAccessibility
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens
import com.tencent.kuikly.demo.pages.sftp.viewer.SftpViewerDispatcherPage

/**
 * SFTP 浏览页（§17.3.2）
 *
 * 输入：路由参数 `connectionId / host / port / user / password / remotePath(可选)`。
 * 流程：
 * 1. `created()` 调 [com.tencent.kuikly.core.module.sftp.SftpModule.connect] → 拿 sessionId
 * 2. `SftpModule.list` 列目录
 * 3. 点击目录进入；点击文件按 MIME 分发到播放/预览
 */
@Page(SftpBrowserPage.PAGE_NAME)
internal class SftpBrowserPage : SftpBasePager() {

    private var connectParam: SftpConnectParam? = null
    private var sessionId: String? = null
    private var connectionId: String = ""
    private var connectionLabel: String = ""
    // 必须可观察：异步回调改了状态要能触发重渲染。
    // entries 用 ObservableList，配合 vfor 才能按 diff 增删行（普通 List 在
    // vif 分支条件不变时不会重建，切目录后列表会一直是旧的）。
    private var entries by observableList<SftpEntry>()
    private var loading: Boolean by observable(true)
    private var errorMsg: String? by observable(null)
    private var currentPath: String by observable("/")

    // —— 收藏两态（未收藏 ☆ / 已收藏 ★）——
    // 当前连接下已收藏的远端路径集合；favoriteVersion 为 observable，供 vif/attr 读取以触发重渲染。
    private val favoritePathSet = mutableSetOf<String>()
    private var favoriteVersion by observable(0)

    // —— 缓存（目录 / 单文件）——
    /** 本地缓存根目录（空 = 本端不支持，入口隐藏，绝不伪报成功） */
    private var cacheRoot: String = ""
    private var cacheSupported: Boolean by observable(false)
    /** 本端是否支持复制路径（Web/桌面 true；其它端隐藏按钮） */
    private var clipboardSupported: Boolean by observable(false)
    /** 本端是否支持本地文件系统（决定是否显示双栏入口） */
    private var localFsSupported: Boolean by observable(false)
    /** 超过该体积先弹确认（可被路由参数 cacheConfirmBytes 覆盖，便于小体积验证） */
    private var cacheConfirmBytes: Long = CacheEngine.NEED_CONFIRM_BYTES
    private var cacheBarText: String by observable("")
    private var cacheBarVisible: Boolean by observable(false)
    /** 缓存任务浮层（页内，避免缓存中跳页丢状态） */
    private var cachePanelVisible: Boolean by observable(false)
    private var cacheTasks by observableList<CacheTask>()
    private var cacheVersionSeen: Int = -1
    private var cacheConfirmVisible: Boolean by observable(false)
    private var cacheConfirmText: String by observable("")
    // 待确认任务（确认后入队）
    private var pendingDir: SftpEntry? = null
    private var pendingDirFiles: List<CacheFile> = emptyList()
    private var pendingFile: SftpEntry? = null
    private var cachePollRef: String? = null

    /** 缓存任务 I/O：列目录（递归求体积）+ 下载落盘（宿主本地绝对路径） */
    private val cacheIo: CacheIo = object : CacheIo {
        override fun list(sessionId: String, path: String, callback: (List<SftpEntry>, SftpError?) -> Unit) {
            sftpModule().list(sessionId, path) { items, _, err -> callback(items, err) }
        }

        override fun download(sessionId: String, remotePath: String, localPath: String, callback: (Boolean, SftpError?) -> Unit) {
            sftpModule().download(sessionId, remotePath, localPath) { _, _, err -> callback(err == null, err) }
        }
    }

    override fun created() {
        super.created()
        val params = pageData.params
        connectParam = SftpConnectParam.fromJson(params)
        connectionId = params.optString("connectionId", "")
        connectionLabel = params.optString("connectionLabel", "")
        currentPath = params.optString("remotePath", "/")
        // 缓存能力：本地缓存根目录（Web/桌面由宿主注入；其它端为空 → 入口隐藏）
        cacheRoot = runCatching {
            acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).cacheRoot()
        }.getOrDefault("")
        cacheSupported = cacheRoot.isNotEmpty()
        clipboardSupported = runCatching {
            acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).supportsClipboard()
        }.getOrDefault(false)
        localFsSupported = runCatching {
            acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).supportsLocalFs()
        }.getOrDefault(false)
        cacheConfirmBytes = params.optLong("cacheConfirmBytes", CacheEngine.NEED_CONFIRM_BYTES)
        doConnectAndList()
        scheduleCachePoll()
    }

    override fun pageWillDestroy() {
        super.pageWillDestroy()
        cachePollRef?.let { clearTimeout(it) }
    }

    /** 回车=确定：缓存过大确认弹窗打开时确认 */
    override fun onEnterKey() {
        if (cacheConfirmVisible) confirmCache()
    }

    /** 轮询缓存进度以刷新顶部悬浮条与浮层（CacheManager 为全局单例，跨页面） */
    private fun scheduleCachePoll() {
        cachePollRef = setTimeout(350) {
            refreshCacheState()
            scheduleCachePoll()
        }
    }

    private fun refreshCacheState() {
        // 有任意任务（含已完成）就保留悬浮条，便于任务结束后仍能打开浮层看结果/清空
        cacheBarVisible = CacheManager.hasAny()
        cacheBarText = CacheManager.barSummary()
        if (CacheManager.version != cacheVersionSeen) {
            cacheVersionSeen = CacheManager.version
            val snap = CacheManager.snapshot()
            cacheTasks.clear()
            cacheTasks.addAll(snap)
        }
    }

    private fun doConnectAndList() {
        val conn = connectParam ?: run {
            errorMsg = "缺少连接参数"
            loading = false
            return
        }
        loading = true
        errorMsg = null
        sftpModule().connect(conn) { sid, err ->
            if (err != null || sid == null) {
                errorMsg = err?.msg ?: "连接失败"
                loading = false
            } else {
                sessionId = sid
                doList()
            }
        }
    }

    private fun doList() {
        val sid = sessionId ?: return
        sftpModule().list(sid, currentPath) { items, _, err ->
            if (err != null) {
                errorMsg = if (err.code == com.tencent.kuikly.core.module.sftp.SftpErrorCode.NO_SUCH_FILE.code ||
                    err.code == com.tencent.kuikly.core.module.sftp.SftpErrorCode.NO_SUCH_PATH.code ||
                    err.msg.contains("No such") || err.msg.contains("not exist")
                ) "目录不存在，可能已被删除" else err.msg
                loading = false
            } else {
                entries.clear()
                // 按名称从小到大排序（不区分大小写；同名再按原串，保证稳定）
                entries.addAll(items.sortedWith(compareBy({ it.name.lowercase() }, { it.name })))
                loading = false
                errorMsg = null
                // 列目录后刷新收藏两态（行内/当前目录按钮据此切换 ☆/★）
                refreshFavorites()
            }
        }
    }

    /** 返回：**优先回到上一级目录**；已在根目录时才断开并关闭页面。 */
    private fun onBackPressed() {
        val parent = parentOf(currentPath)
        if (parent != currentPath) {
            currentPath = parent
            doList()
            return
        }
        // 已到顶层
        val sid = sessionId
        if (sid != null) {
            sftpModule().disconnect(sid) {
                acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
            }
        } else {
            acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
        }
    }

    /** 「✕」：无论当前在哪一级目录，直接退出文件列表（断开会话后关页） */
    private fun closeSelf() {
        val sid = sessionId
        if (sid != null) {
            sftpModule().disconnect(sid) {
                acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
            }
        } else {
            acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
        }
    }

    /** 取上一级目录；"/" 或空串返回自身（表示已是顶层）。 */
    private fun parentOf(path: String): String {
        val p = path.trimEnd('/')
        if (p.isEmpty()) return "/"
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) "/" else p.substring(0, idx)
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            // 顶部安全区：Android 沉浸式 / 刘海屏下，页面自绘导航栏会被状态栏遮挡，
            // 且状态栏区域会吞掉点击（表现为「+ 新建」点不动）。这里整体下移状态栏高度。
            View {
                attr {
                    width(pagerData.pageViewWidth)
                    height(pagerData.pageViewHeight)
                    paddingTop(pagerData.statusBarHeight)
                }

            attr { backgroundColor(SftpColorTokens.bg) }

            // 顶部导航
            View {
                attr {
                    size(pagerData.pageViewWidth, 56f)
                    flexDirectionRow()
                    alignItemsCenter()
                    padding(16f, 8f, 16f, 8f)
                    backgroundColor(SftpColorTokens.cardBg)
                }
                // 最前面的「✕」：直接退出文件列表（关页，回首页）
                View {
                    attr { size(36f, 36f); allCenter(); accessibility("browser_exit") }
                    event { click { ctx.closeSelf() } }
                    Text { attr { text("✕"); fontSize(20f); color(SftpColorTokens.textPrimary) } }
                }
                View {
                    attr { size(36f, 36f); allCenter(); accessibility(SftpAccessibility.BTN_BACK) }
                    event { click { ctx.onBackPressed() } }
                    Text { attr { text("<"); fontSize(22f); color(SftpColorTokens.textPrimary) } }
                }
                Text {
                    attr {
                        text(ctx.currentPath)
                        fontSize(15f)
                        color(SftpColorTokens.textPrimary)
                        flex(1f)
                        marginLeft(8f)
                    }
                }
                // 复制当前路径（Web/桌面）：方便把有问题的路径发出来
                vif({ ctx.clipboardSupported }) {
                    View {
                        attr {
                            width(38f); height(38f); allCenter()
                            accessibility("copy_path")
                        }
                        event { click { ctx.copyCurrentPath() } }
                        Text { attr { text("⧉"); fontSize(17f); color(SftpColorTokens.primary) } }
                    }
                }
                // 右上角：收藏当前目录（两态按钮：未收藏 ☆ / 已收藏 ★）
                vif({ ctx.isFavoritePath(ctx.currentPath) }) {
                    View {
                        attr { width(38f); height(38f); allCenter(); accessibility("unfavorite_current_dir") }
                        event { click { ctx.toggleFavoriteDir() } }
                        Text { attr { text("★"); fontSize(17f); color(SftpColorTokens.primary) } }
                    }
                }
                velse {
                    View {
                        attr { width(38f); height(38f); allCenter(); accessibility("favorite_current_dir") }
                        event { click { ctx.toggleFavoriteDir() } }
                        Text { attr { text("☆"); fontSize(17f); color(SftpColorTokens.textSecondary) } }
                    }
                }
                // 右上角：切到双栏（本地 ↔ 当前远端目录）—— 需宿主本地文件能力
                vif({ ctx.localFsSupported }) {
                    View {
                        attr { size(36f, 36f); allCenter(); accessibility("dual_pane_entry") }
                        event { click { ctx.openDualPane() } }
                        Text { attr { text("⇄"); fontSize(20f); color(SftpColorTokens.primary) } }
                    }
                }
            }

            // 内容区三态
            View {
                attr { flex(1f) }
                // 用条件指令而非 Kotlin when：条件 lambda 内的读取会被依赖收集，
                // 异步拿到列表后才能从 loading 切到列表（空态/错误态同理）
                vif({ ctx.loading }) {
                    SftpLoadingView()
                }
                velseif({ ctx.errorMsg != null }) {
                    SftpErrorView(ctx.errorMsg ?: "") { ctx.doConnectAndList() }
                }
                velseif({ ctx.entries.isEmpty() }) {
                    SftpEmptyView("空目录")
                }
                velse {
                    SftpEntriesView(
                        entriesProvider = { ctx.entries },
                        onClick = { entry -> ctx.onEntryClick(entry) },
                        isFavorited = { path -> ctx.isFavoritePath(path) },
                        onToggleFavorite = { e -> ctx.toggleFavoriteEntry(e) },
                        onCache = if (ctx.cacheSupported) { { e -> ctx.requestCache(e) } } else null,
                    )
                }
            }

            // 顶部缓存悬浮条（有进行中/暂停任务时出现；放在内容区之后以便覆盖在上层）
            vif({ ctx.cacheBarVisible }) {
                View {
                    attr {
                        positionAbsolute()
                        left(12f)
                        bottom(12f)
                        width(pagerData.pageViewWidth - 24f)
                        height(34f)
                        borderRadius(17f)
                        padding(10f, 6f, 10f, 6f)
                        backgroundColor(SftpColorTokens.primary)
                        flexDirectionRow()
                        alignItemsCenter()
                        zIndex(60)
                        accessibility("cache_bar")
                    }
                    event { click { ctx.openCachePanel() } }
                    Text { attr { text("⬇ " + ctx.cacheBarText); fontSize(13f); color(Color(0xFFFFFFFF.toInt())); flex(1f) } }
                    Text { attr { text("查看 ›"); fontSize(13f); color(Color(0xFFFFFFFF.toInt())) } }
                }
            }

            // 过大确认弹层（绝对定位；放在最后以免被内容盖住）
            vif({ ctx.cacheConfirmVisible }) {
                View {
                    attr {
                        positionAbsolute(); left(0f); top(0f)
                        width(pagerData.pageViewWidth)
                        height(pagerData.pageViewHeight)
                        backgroundColor(Color(0x99000000.toInt()))
                        allCenter()
                        zIndex(80)
                        accessibility("cache_confirm_dialog")
                    }
                    View {
                        attr {
                            width(300f)
                            padding(24f, 20f, 24f, 20f)
                            backgroundColor(SftpColorTokens.cardBg)
                            borderRadius(12f)
                            flexDirectionColumn()
                        }
                        Text {
                            attr {
                                text("缓存体积较大")
                                fontSize(16f); fontWeightBold(); color(SftpColorTokens.textPrimary); marginBottom(8f)
                            }
                        }
                        Text {
                            attr {
                                text(ctx.cacheConfirmText)
                                fontSize(13f); color(SftpColorTokens.textSecondary); marginBottom(16f)
                            }
                        }
                        View {
                            attr { flexDirectionRow(); width(252f) }
                            View {
                                attr {
                                    flex(1f); height(40f); allCenter()
                                    backgroundColor(SftpColorTokens.divider); borderRadius(8f); marginRight(8f)
                                    accessibility("cache_confirm_no")
                                }
                                event { click { ctx.dismissCacheConfirm() } }
                                Text { attr { text("取消"); fontSize(14f); color(SftpColorTokens.textPrimary) } }
                            }
                            View {
                                attr {
                                    flex(1f); height(40f); allCenter()
                                    backgroundColor(SftpColorTokens.primary); borderRadius(8f)
                                    accessibility("cache_confirm_yes")
                                }
                                event { click { ctx.confirmCache() } }
                                Text { attr { text("继续"); fontSize(14f); color(Color(0xFFFFFFFF.toInt())) } }
                            }
                        }
                    }
                }
            }

            // 缓存任务浮层（页内；缓存中打开，避免跳页丢状态）
            vif({ ctx.cachePanelVisible }) {
                CacheTasksOverlay(
                    tasksProvider = { ctx.cacheTasks },
                    onChanged = { ctx.refreshCacheState() },
                    onClose = { ctx.cachePanelVisible = false },
                )
            }
                    }
}
    }

    /** 切到双栏：只传 connectionId（凭据不出现在 URL/history），远端栏定位当前目录。 */
    internal fun openDualPane() {
        val router = acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        if (connectionId.isNotEmpty()) {
            SftpPageNames.openDualPane(router, connectionId, connectionLabel, remoteHome = "/", remotePath = currentPath)
            return
        }
        // 内联凭据入口（深链/测试）：先把连接落到连接库（加密存储），再用 id 打开，避免密钥进 URL
        val c = connectParam?.takeIf { it.host.isNotEmpty() } ?: run {
            SftpPageNames.openDualPane(router, "", "", remoteHome = "/", remotePath = currentPath)
            return
        }
        val conn = SftpConnection(
            id = SftpConnection.buildId(c.host, c.port, c.user),
            label = "${c.user}@${c.host}",
            host = c.host,
            port = c.port,
            user = c.user,
            authMethod = c.authMethod,
            password = c.password,
            privateKey = c.privateKey,
            passphrase = c.passphrase,
        )
        sftpConnectionModule().get(conn.id) { existing, _ ->
            if (existing != null) {
                SftpPageNames.openDualPane(router, conn.id, existing.label, remoteHome = "/", remotePath = currentPath)
            } else {
                sftpConnectionModule().add(conn) { id, _ ->
                    SftpPageNames.openDualPane(router, id ?: conn.id, conn.label, remoteHome = "/", remotePath = currentPath)
                }
            }
        }
    }

    /** 当前连接下该远端路径是否已收藏（读 favoriteVersion 以在 vif/attr 内建立响应式依赖）。 */
    internal fun isFavoritePath(path: String): Boolean {
        val rev = favoriteVersion   // 读取 observable：在 vif/attr 内调用可建立响应式依赖
        return rev >= 0 && favoritePathSet.contains(path)
    }

    /** 拉取当前连接的收藏，刷新两态按钮（列目录后 / 收藏变更后调用）。 */
    private fun refreshFavorites() {
        sftpFavoritesModule().list(connectionId.takeIf { it.isNotEmpty() }) { items, _ ->
            val set = items.filter { it.connectionId == connectionId }.map { it.remotePath }.toSet()
            favoritePathSet.clear()
            favoritePathSet.addAll(set)
            favoriteVersion++
        }
    }

    /** 本地即时更新某路径的收藏态（不等待原生回包刷新，交互更跟手）。 */
    private fun markFavoriteLocal(path: String, favorited: Boolean) {
        if (favorited) favoritePathSet.add(path) else favoritePathSet.remove(path)
        favoriteVersion++
    }

    /** 收藏当前目录：未收藏 → 收藏；已收藏 → 取消（两态按钮）。 */
    internal fun toggleFavoriteDir() {
        if (connectionId.isEmpty()) { Utils.bridgeModule(this).toast("请先从连接列表进入"); return }
        val path = currentPath
        val id = connectionId + "::" + path
        if (isFavoritePath(path)) {
            sftpFavoritesModule().remove(id) { ok, err ->
                if (err != null || !ok) {
                    Utils.bridgeModule(this).toast("取消收藏失败：" + (err?.msg ?: ""))
                } else {
                    markFavoriteLocal(path, false)
                    Utils.bridgeModule(this).toast("已取消收藏目录")
                }
            }
        } else {
            val fav = com.tencent.kuikly.core.module.sftp.SftpFavorite(
                id = id,
                connectionId = connectionId,
                connectionLabel = connectionLabel,
                remotePath = path,
                name = path.substringAfterLast('/').ifEmpty { "/" },
                isDir = true,
                size = 0L,
                starredAt = com.tencent.kuikly.core.datetime.DateTime.currentTimestamp(),
            )
            sftpFavoritesModule().add(fav) { _, err ->
                if (err != null) {
                    Utils.bridgeModule(this).toast("收藏失败：" + err.msg)
                } else {
                    markFavoriteLocal(path, true)
                    Utils.bridgeModule(this).toast("已收藏目录")
                }
            }
        }
    }

    /** 收藏一个条目：未收藏 → 收藏；已收藏 → 取消（文件与目录都适用）。 */
    internal fun toggleFavoriteEntry(entry: SftpEntry) {
        val id = connectionId + "::" + entry.path
        if (isFavoritePath(entry.path)) {
            sftpFavoritesModule().remove(id) { ok, err ->
                if (err != null || !ok) {
                    Utils.bridgeModule(this).toast("取消收藏失败：" + (err?.msg ?: ""))
                } else {
                    markFavoriteLocal(entry.path, false)
                    Utils.bridgeModule(this).toast(if (entry.isDir) "已取消收藏目录" else "已取消收藏文件")
                }
            }
        } else {
            val fav = com.tencent.kuikly.core.module.sftp.SftpFavorite(
                id = id,
                connectionId = connectionId,
                connectionLabel = connectionLabel,
                remotePath = entry.path,
                name = entry.name,
                isDir = entry.isDir,
                size = entry.size,
                starredAt = com.tencent.kuikly.core.datetime.DateTime.currentTimestamp(),
            )
            sftpFavoritesModule().add(fav) { _, err ->
                if (err != null) {
                    Utils.bridgeModule(this).toast("收藏失败：" + err.msg)
                } else {
                    markFavoriteLocal(entry.path, true)
                    Utils.bridgeModule(this).toast(if (entry.isDir) "已收藏目录" else "已收藏文件")
                }
            }
        }
    }

    /** 请求缓存一个条目：目录递归展开后统计体积；单文件直接入队（用户要求单文件也可缓存）。 */
    internal fun requestCache(entry: SftpEntry) {
        if (!cacheSupported) {
            Utils.bridgeModule(this).toast("本端暂不支持缓存到本地")
            return
        }
        val sid = sessionId ?: ""
        if (entry.isDir) {
            Utils.bridgeModule(this).toast("正在统计目录体积…")
            CacheManager.measureDir(cacheIo, sid, entry.path) { files, total, err ->
                if (err != null) {
                    Utils.bridgeModule(this).toast("统计失败：" + err.msg)
                    return@measureDir
                }
                if (files.isEmpty()) {
                    Utils.bridgeModule(this).toast("目录为空")
                    return@measureDir
                }
                if (total > cacheConfirmBytes) {
                    pendingDir = entry
                    pendingDirFiles = files
                    cacheConfirmText = "${entry.name} · ${files.size} 个文件 · ${formatSize(total)}，是否继续？"
                    cacheConfirmVisible = true
                } else {
                    CacheManager.enqueueDir(cacheIo, sid, entry.path, entry.name, cacheRoot, files)
                    Utils.bridgeModule(this).toast("已加入缓存：${entry.name}")
                    refreshCacheState()
                }
            }
        } else {
            if (entry.size > cacheConfirmBytes) {
                pendingFile = entry
                cacheConfirmText = "${entry.name} · ${formatSize(entry.size)}，是否继续？"
                cacheConfirmVisible = true
            } else {
                CacheManager.enqueueFile(cacheIo, sid, entry, cacheRoot)
                Utils.bridgeModule(this).toast("已加入缓存：${entry.name}")
                refreshCacheState()
            }
        }
    }

    /** 确认弹层「继续」：把待确认任务入队 */
    internal fun confirmCache() {
        cacheConfirmVisible = false
        val dir = pendingDir
        val file = pendingFile
        val files = pendingDirFiles
        pendingDir = null
        pendingFile = null
        pendingDirFiles = emptyList()
        val sid = sessionId ?: ""
        if (dir != null) {
            CacheManager.enqueueDir(cacheIo, sid, dir.path, dir.name, cacheRoot, files)
            Utils.bridgeModule(this).toast("已加入缓存：${dir.name}")
        } else if (file != null) {
            CacheManager.enqueueFile(cacheIo, sid, file, cacheRoot)
            Utils.bridgeModule(this).toast("已加入缓存：${file.name}")
        }
        refreshCacheState()
    }

    internal fun dismissCacheConfirm() {
        cacheConfirmVisible = false
        pendingDir = null
        pendingFile = null
        pendingDirFiles = emptyList()
    }

    /** 复制当前远端路径（含 host，便于反馈问题）到系统剪贴板 */
    internal fun copyCurrentPath() {
        val host = connectParam?.host ?: ""
        val full = if (host.isNotEmpty()) "$host:$currentPath" else currentPath
        runCatching { acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).copyToClipboard(full) }
        Utils.bridgeModule(this).toast("路径已复制：" + full)
    }

    internal fun openCachePanel() {        refreshCacheState()
        cachePanelVisible = true
    }

    private fun onEntryClick(entry: SftpEntry) {
        if (entry.isDir) {
            currentPath = entry.path
            doList()
            return
        }
        val sid = sessionId ?: return
        val mime = MimeExtMap.mimeOfPath(entry.path)

        val params = JSONObject()
        params.put("sessionId", sid)
        params.put("connectionId", connectionId)
        params.put("connectionLabel", connectionLabel)
        params.put("remotePath", entry.path)
        params.put("name", entry.name)
        params.put("size", entry.size)

        val router = acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        when {
            MimeExtMap.isVideo(mime) || MimeExtMap.isAudio(mime) -> {
                // 视频或音频 → 播放页（§17.3.3 / §20）
                // 桌面壳会开独立窗口（可同时播多个），其它端页内路由
                openPlayerPage(params)
            }
            MimeExtMap.isMarkdown(entry.path) || MimeExtMap.isHtml(entry.path) || MimeExtMap.isText(mime) -> {
                // 文本类（Markdown / HTML / 纯文本 / 代码）→ 查看器；桌面壳另开独立窗口
                openViewerPage(params)
            }
            else -> {
                // 图片 / PDF 等 → 预览分发页（§4.1 / §17.3.3.3）
                router.openPage(SftpViewerDispatcherPage.PAGE_NAME, params)
            }
        }
    }

    companion object {
        const val PAGE_NAME = "SftpBrowserPage"
    }
}

/** 目录项列表渲染 */
internal fun ViewContainer<*, *>.SftpEntriesView(
    entriesProvider: () -> ObservableList<SftpEntry>,
    onClick: (SftpEntry) -> Unit,
    isFavorited: (String) -> Boolean = { false },
    onToggleFavorite: (SftpEntry) -> Unit = { },
    onCache: ((SftpEntry) -> Unit)? = null
) {
    // 必须放在滚动容器里：此前行直接铺在普通 View 上，没有滚动能力，
    // 目录条目超过一屏后就再也够不到（文件浏览器基本不可用）。
    Scroller {
        attr {
            flex(1f)
            width(pagerData.pageViewWidth)
            showScrollerIndicator(true)
            flexDirectionColumn()
            backgroundColor(SftpColorTokens.bg)
        }
        // 用 vfor：条目列表变化时按 diff 增删行。
        // 若直接把 List 作为入参放进 vif 分支，分支条件（isEmpty）不变时不会重建，
        // 切目录后列表会一直是旧的（表现为「点了没反应」）。
        vfor(entriesProvider) { entry ->
            View {
                attr {
                    // 预留滚动条宽度，否则右侧大小列被裁掉
                    width(pagerData.pageViewWidth - 8f)
                    padding(16f, 12f, 16f, 12f)
                    backgroundColor(SftpColorTokens.cardBg)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                event { click { onClick(entry) } }
                Text {
                    attr {
                        text(if (entry.isDir) "📁 " else "📄 ")
                        fontSize(18f)
                    }
                }
                Text {
                    attr {
                        text(entry.name)
                        fontSize(15f)
                        color(SftpColorTokens.textPrimary)
                        flex(1f)
                        marginLeft(8f)
                    }
                }
                if (!entry.isDir) {
                    Text {
                        attr {
                            text(formatSize(entry.size))
                            fontSize(12f)
                            color(SftpColorTokens.textSecondary)
                        }
                    }
                }
                // 行内收藏两态按钮：未收藏 ☆ / 已收藏 ★（文件与目录都可收藏）。
                // 必须在 vif 的 lambda 内读收藏态，依赖才会被收集（结构层读取不会触发重渲染）。
                vif({ isFavorited(entry.path) }) {
                    View {
                        attr { width(38f); height(38f); allCenter(); accessibility("unfavorite_entry") }
                        event { click { onToggleFavorite(entry) } }
                        Text { attr { text("★"); fontSize(15f); color(SftpColorTokens.primary) } }
                    }
                }
                velse {
                    View {
                        attr { width(38f); height(38f); allCenter(); accessibility("favorite_entry") }
                        event { click { onToggleFavorite(entry) } }
                        Text { attr { text("☆"); fontSize(15f); color(SftpColorTokens.textSecondary) } }
                    }
                }
                // 行内缓存（目录递归缓存 / 单文件缓存）：仅在支持本地缓存的端显示
                if (onCache != null) {
                    View {
                        attr {
                            width(38f)
                            height(38f)
                            allCenter()
                            accessibility("cache_entry")
                        }
                        event { click { onCache(entry) } }
                        Text { attr { text("⬇"); fontSize(15f); color(SftpColorTokens.primary) } }
                    }
                }
            }
        }
    }
}

/** 字节大小人类可读（commonMain 无 String.format，手动拼接） */
internal fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return formatOneDecimal(kb) + "K"
    val mb = kb / 1024.0
    if (mb < 1024) return formatOneDecimal(mb) + "M"
    val gb = mb / 1024.0
    return formatOneDecimal(gb) + "G"
}

/** 保留 1 位小数，不依赖 String.format */
internal fun formatOneDecimal(v: Double): String {
    val scaled = (v * 10).toLong()
    val intPart = scaled / 10
    val fracPart = scaled % 10
    return "$intPart.$fracPart"
}
