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
import com.tencent.kuikly.core.module.sftp.I18n
import com.tencent.kuikly.core.module.sftp.SftpConnection
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.demo.pages.base.BridgeModule
import com.tencent.kuikly.demo.pages.base.Utils
import com.tencent.kuikly.demo.pages.sftp.cache.CacheManager
import com.tencent.kuikly.demo.pages.sftp.cache.CacheTask
import com.tencent.kuikly.demo.pages.sftp.cache.CacheTasksOverlay
import com.tencent.kuikly.core.utils.PlatformUtils
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpAccessibility
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * SFTP 首页（§17.3.1 / §22.3.2 第 1 步）
 *
 * Tab 结构：连接列表 / 收藏 / 历史。
 * 「+ 新建连接」按钮跳 [SftpConnectEditPage]。
 * 点击连接项跳 [SftpBrowserPage]。
 *
 * 注：Phase 0.3 先实现 UI 骨架 + 路由跳转；连接持久化接入在 Phase 5。
 */
@Page(SftpHomePage.PAGE_NAME)
internal class SftpHomePage : SftpBasePager() {

    // NOTE: every field read by body() must be `observable`, otherwise mutations made
    // from async module callbacks will not trigger a re-render (the page would stay on
    // its initial state forever, e.g. stuck on the loading view).
    private var connections by observableList<SftpConnection>()
    /** 首次出现由 created() 拉取，避免 pageDidAppear 重复请求 */
    private var hasAppearedOnce = false
    private var loading: Boolean by observable(true)
    private var errorMsg: String? by observable(null)
    private var currentTab: Int by observable(0)  // 0 连接 / 1 收藏 / 2 历史
    /** 右下角「更多」抽屉（设置 / 缓存列表 / 关于） */
    private var moreVisible: Boolean by observable(false)
    /** 缓存任务浮层（与浏览页共用同一 CacheManager） */
    private var cachePanelVisible: Boolean by observable(false)
    private var cacheTasks by observableList<CacheTask>()
    private var cacheVersionSeen: Int = -1
    private var cachePollRef: String? = null
    // 收藏 Tab 状态
    internal var favorites by observableList<com.tencent.kuikly.core.module.sftp.SftpFavorite>()
    internal var favoritesLoaded: Boolean by observable(false)
    internal var favoritesError: String? by observable(null)
    // 历史 Tab 状态
    internal var history by observableList<com.tencent.kuikly.core.module.sftp.SftpPlaybackRecord>()
    internal var historyLoaded: Boolean by observable(false)
    internal var historyError: String? by observable(null)

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

            // 顶部导航栏
            View {
                attr {
                    size(pagerData.pageViewWidth, 56f)
                    flexDirectionRow()
                    alignItemsCenter()
                    padding(16f, 8f, 16f, 8f)
                    backgroundColor(SftpColorTokens.cardBg)
                }
                Text {
                    attr {
                        text("SFTP 客户端")
                        fontSize(18f)
                        fontWeightBold()
                        color(SftpColorTokens.textPrimary)
                        flex(1f)
                    }
                }
                // 双栏文件管理器入口（仅 Web/桌面：依赖宿主 window.localFs 提供本地栏）
                vif({ ctx.isWebLike }) {
                    View {
                        attr {
                            size(36f, 36f)
                            allCenter()
                            accessibility("dual_pane_entry")
                        }
                        event {
                            click {
                                val c = ctx.connections.maxByOrNull { it.lastUsedAt }
                                if (c == null) {
                                    ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                                        .openPage(SftpConnectEditPage.PAGE_NAME)
                                } else {
                                    ctx.openDualPane(c)
                                }
                            }
                        }
                        Text {
                            attr {
                                text("⇄")
                                fontSize(20f)
                                color(SftpColorTokens.primary)
                            }
                        }
                    }
                }
                // 设置入口（直接可达；右下角「更多」FAB 在桌面端可能因根视图尺寸不跟随而落在可视区外）
                View {
                    attr {
                        size(36f, 36f)
                        allCenter()
                        accessibility("home_settings_entry")
                    }
                    event {
                        click {
                            ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(SftpPageNames.SETTINGS)
                        }
                    }
                    Text {
                        attr {
                            text("⚙")
                            fontSize(19f)
                            color(SftpColorTokens.primary)
                        }
                    }
                }
                View {
                    attr {
                        size(36f, 36f)
                        allCenter()
                        accessibility(SftpAccessibility.BTN_CONNECT)
                    }
                    event {
                        click {
                            ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                                .openPage(SftpConnectEditPage.PAGE_NAME)
                        }
                    }
                    Text {
                        attr {
                            text("+")
                            fontSize(28f)
                            color(SftpColorTokens.primary)
                        }
                    }
                }
            }

            // Tab 栏
            // 传入 provider 而非取值：读取必须发生在 attr{} / vif 条件 lambda 内部，
            // 这样 Kuikly 的依赖收集才能记录到 currentTab（§ reactive 约定）。
            SftpTabBar({ ctx.currentTab }) { newTab -> ctx.onTabChange(newTab) }

            // 内容区
            // 必须用 vif/velseif/velse 条件指令而非 Kotlin `when`：
            // 1) `when` 只在 body() 首次求值时决定结构，之后 observable 变化不会重建
            //    结构，页面会永远停在首帧的 loading 态；
            // 2) 每个数据态都必须体现为一个**条件**（而不只是 creator 里的入参），
            //    否则上一层级条件不变时 creator 不会重跑，列表内容会冻结在空态。
            View {
                attr { flex(1f) }
                vif({ ctx.loading }) {
                    SftpLoadingView()
                }
                velseif({ ctx.errorMsg != null }) {
                    SftpErrorView(ctx.errorMsg ?: "") { ctx.refresh() }
                }

                // —— 连接 Tab ——
                velseif({ ctx.currentTab == 0 }) {
                    View {
                        attr { flex(1f); flexDirectionColumn(); width(pagerData.pageViewWidth) }
                        // 本地文件管理（双栏；远端栏可随时选主机）—— 仅 Web/桌面
                        if (ctx.isWebLike) {
                            // 本地那一栏：本地文件管理 + 右侧「终端」格（各自独立点击，避免冒泡）
                            View {
                                attr {
                                    width(pagerData.pageViewWidth - 8f)
                                    flexDirectionRow()
                                    alignItemsCenter()
                                }
                                SftpLocalFileManagerEntry(
                                    onClick = { ctx.openLocalFileManager() },
                                    fillWidth = false,
                                )
                                // 本地终端只存在于 Web/桌面（浏览器/Electron 有本地 shell）；原生端无本地 shell
                                if (ctx.isWebLike && ctx.terminalSupported()) {
                                    View {
                                        attr {
                                            width(56f)
                                            height(64f)
                                            allCenter()
                                            backgroundColor(SftpColorTokens.cardBg)
                                            accessibility("local_terminal_entry")
                                        }
                                        event { click { ctx.openTerminal(null) } }
                                        Text { attr { text(">_"); fontSize(16f); color(SftpColorTokens.primary) } }
                                    }
                                }
                            }
                        }
                        vif({ ctx.connections.isEmpty() }) {
                            SftpEmptyView("暂无连接，点 + 新建")
                        }
                        velse {
                            SftpConnectionListView(
                                { ctx.connections },
                                { conn -> ctx.openBrowser(conn) },
                                if (ctx.isWebLike) { { conn -> ctx.openDualPane(conn) } } else null,
                                // 远程终端：所有端只要支持 shell（supportsTerminal）即显示入口
                                if (ctx.terminalSupported()) { { conn -> ctx.openTerminal(conn) } } else null
                            )
                        }
                    }
                }

                // —— 收藏 Tab ——
                velseif({ ctx.currentTab == 1 && ctx.favoritesError != null }) {
                    SftpErrorView(ctx.favoritesError ?: "") { ctx.reloadFavorites() }
                }
                velseif({ ctx.currentTab == 1 && ctx.favorites.isEmpty() }) {
                    SftpEmptyView("暂无收藏")
                }
                velseif({ ctx.currentTab == 1 }) {
                    SftpFavoritesList({ ctx.favorites }) { fav -> ctx.openFavorite(fav) }
                }

                // —— 历史 Tab ——
                velseif({ ctx.historyError != null }) {
                    SftpErrorView(ctx.historyError ?: "") { ctx.reloadHistory() }
                }
                velseif({ ctx.history.isEmpty() }) {
                    SftpEmptyView("暂无播放历史")
                }
                velse {
                    SftpHistoryList({ ctx.history }, { rec -> ctx.openHistory(rec) }) { rec -> ctx.deleteHistory(rec) }
                }

                // 右下角「更多」按钮（仅 Web/桌面）
                if (ctx.isWebLike) {
                    View {
                        attr {
                            positionAbsolute()
                            right(16f)
                            bottom(16f)
                            zIndex(99)
                            size(48f, 48f)
                            borderRadius(24f)
                            backgroundColor(SftpColorTokens.primary)
                            allCenter()
                            accessibility("home_more_fab")
                        }
                        event { click { ctx.moreVisible = true } }
                        Text { attr { text("＋"); fontSize(24f); color(Color(0xFFFFFFFF.toInt())) } }
                    }
                }

                // 「更多」抽屉（绝对定位；必须放在内容区之后，避免被盖住）
                vif({ ctx.moreVisible }) {
                    View {
                        attr {
                            positionAbsolute(); left(0f); bottom(0f)
                            width(pagerData.pageViewWidth)
                            zIndex(98)
                            backgroundColor(Color(0x99000000))
                            flexDirectionColumn()
                        }
                        event { click { ctx.moreVisible = false } }
                        View {
                            attr {
                                width(pagerData.pageViewWidth)
                                height(240f)
                                backgroundColor(SftpColorTokens.cardBg)
                                borderRadius(14f)
                                padding(16f, 12f, 16f, 12f)
                                flexDirectionColumn()
                            }
                            event { click { } }
                            Text { attr { text("更多"); fontSize(15f); fontWeightBold(); color(SftpColorTokens.textPrimary); marginBottom(8f) } }
                            MoreRow("设置", "历史条数 / 清空缓存 / 清空播放历史") {
                                ctx.moreVisible = false
                                ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(SftpPageNames.SETTINGS)
                            }
                            MoreRow("缓存列表", "正在缓存 / 已缓存") {
                                ctx.moreVisible = false
                                ctx.refreshCacheState()
                                ctx.cachePanelVisible = true
                            }
                            MoreRow("关于", "Kuikly SFTP 0.1.0") {
                                ctx.moreVisible = false
                                Utils.bridgeModule(ctx).toast("Kuikly SFTP 0.1.0")
                            }
                        }
                    }
                }
            }

            // 缓存任务浮层（与浏览页共用 CacheManager）
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

    internal fun reloadFavorites() {
        favoritesLoaded = true
        favoritesError = null
        sftpFavoritesModule().list { items, error ->
            favorites.clear()
            favorites.addAll(items)
            favoritesError = error?.msg
        }
    }

    internal fun reloadHistory() {
        historyLoaded = true
        historyError = null
        sftpPlaybackHistoryModule().listByConnection("") { items, error ->
            history.clear()
            history.addAll(items)
            historyError = error?.msg
        }
    }

    private fun openBrowser(conn: SftpConnection) {
        // 传整个 SftpConnectParam.toJson() 给 BrowserPage，包含 host/port/user/password/privateKey 等全部字段
        val connectParam = conn.toConnectParam()
        val params = connectParam.toJson()
        // connectionId 用 SftpConnection.id（持久化 id，用于收藏/历史关联）
        params.put("connectionId", conn.id)
        params.put("connectionLabel", conn.label.ifEmpty { conn.user + "@" + conn.host })
        acquireModule<RouterModule>(RouterModule.MODULE_NAME)
            .openPage(SftpBrowserPage.PAGE_NAME, params)
    }

    /**
     * 打开终端（独立窗口；本地终端 [conn]==null，远程终端传连接）。
     * 跨端：web/桌面走独立窗口，其它端回退页内；未实现端由入口处的能力探测隐藏。
     */
    internal fun openTerminal(conn: com.tencent.kuikly.core.module.sftp.SftpConnection?) {
        val p = JSONObject()
        // 渲染器交给页面自选：Web/桌面优先 xterm.js，其它端回退共享网格（renderer=grid 可强制网格）
        if (conn == null) {
            p.put("local", true)
        } else {
            p.put("connectionId", conn.id)
            p.put("connectionLabel", conn.label)
            p.put("host", conn.host)
        }
        val bridge = acquireModule<BridgeModule>(BridgeModule.MODULE_NAME)
        val standalone = runCatching { bridge.supportsPlayerWindow() }.getOrDefault(false)
        if (standalone) {
            val hp = JSONObject(p.toString())
            hp.put("page_name", SftpPageNames.TERMINAL)
            bridge.openPlayerWindow(hp)
        } else {
            acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(SftpPageNames.TERMINAL, p)
        }
    }

    /** 本端是否支持终端（决定是否显示入口；未实现端不显示、也不调用其原生方法） */
    private fun terminalSupported(): Boolean =
        runCatching { acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).supportsTerminal() }.getOrDefault(false)

    /** 本端是否支持本地文件系统（双栏本地栏 / 本地文件管理入口） */
    private fun localFsSupported(): Boolean =
        runCatching { acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).supportsLocalFs() }.getOrDefault(false)

    /** 删除一条播放历史（✕） */
    internal fun deleteHistory(rec: com.tencent.kuikly.core.module.sftp.SftpPlaybackRecord) {
        sftpPlaybackHistoryModule().remove(rec.id) { _, err ->
            if (err != null) {
                Utils.bridgeModule(this).toast("删除失败：" + err.msg)
            } else {
                Utils.bridgeModule(this).toast("已删除历史：" + rec.name)
                reloadHistory()
            }
        }
    }

    /** 默认入口：只开本地栏（远端栏由用户在页内选择主机）。 */
    internal fun openLocalFileManager() {
        SftpPageNames.openDualPane(acquireModule(RouterModule.MODULE_NAME), connectionId = "", label = "")
    }

    /** 以指定连接打开双栏文件管理器（仅 Web/桌面可用；只传 id，凭据由目标页从连接库取）。 */
    internal fun openDualPane(conn: com.tencent.kuikly.core.module.sftp.SftpConnection) {
        SftpPageNames.openDualPane(acquireModule(RouterModule.MODULE_NAME), conn.id, conn.label)
    }

    /** 首页收藏 Tab 点击进入：目录→浏览页；文件→播放页（与收藏详情页一致，只传 connectionId，目标页自行解析凭据） */
    private fun openFavorite(favorite: com.tencent.kuikly.core.module.sftp.SftpFavorite) {
        val params = com.tencent.kuikly.core.nvi.serialization.json.JSONObject()
        params.put("connectionId", favorite.connectionId)
        params.put("connectionLabel", favorite.connectionLabel)
        params.put("remotePath", favorite.remotePath)
        if (favorite.isDir) {
            acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                .openPage(SftpBrowserPage.PAGE_NAME, params)
        } else {
            params.put("name", favorite.name)
            params.put("size", favorite.size)
            openPlayerPage(params)
        }
    }

    /** 首页历史 Tab 点击进入播放页（与历史详情页一致） */
    private fun openHistory(record: com.tencent.kuikly.core.module.sftp.SftpPlaybackRecord) {
        val params = com.tencent.kuikly.core.nvi.serialization.json.JSONObject()
        params.put("connectionId", record.connectionId)
        params.put("connectionLabel", record.connectionLabel)
        params.put("remotePath", record.remotePath)
        params.put("name", record.name)
        params.put("size", record.size)
        openPlayerPage(params)
    }

    private fun onTabChange(newTab: Int) {
        currentTab = newTab
        when (newTab) {
            1 -> if (!favoritesLoaded) reloadFavorites()
            2 -> if (!historyLoaded) reloadHistory()
        }
    }

    override fun created() {
        super.created()
        refresh()
        refreshCacheState()
        scheduleCachePoll()
    }

    override fun pageWillDestroy() {
        super.pageWillDestroy()
        cachePollRef?.let { clearTimeout(it) }
    }

    private fun scheduleCachePoll() {
        cachePollRef = setTimeout(400) {
            refreshCacheState()
            scheduleCachePoll()
        }
    }

    private fun refreshCacheState() {
        if (CacheManager.version != cacheVersionSeen) {
            cacheVersionSeen = CacheManager.version
            val snap = CacheManager.snapshot()
            cacheTasks.clear()
            cacheTasks.addAll(snap)
        }
    }

    /**
     * 首页是导航栈根，从「新建连接 / 浏览」返回时不会被重建，created() 不会再跑；
     * 必须在这里重新拉取，否则新建的连接不会出现在列表里。
     * 首次出现由 created() 负责，这里只在「再次出现」时刷新，避免首屏重复请求。
     */
    override fun pageDidAppear() {
        super.pageDidAppear()
        if (!hasAppearedOnce) {
            hasAppearedOnce = true
            return
        }
        refresh()
        when (currentTab) {
            1 -> reloadFavorites()
            2 -> reloadHistory()
        }
    }

    private fun refresh() {
        loading = true
        errorMsg = null
        sftpConnectionModule().list { items, error ->
            loading = false
            if (error != null) {
                errorMsg = error.msg
            } else {
                connections.clear()
                connections.addAll(items)
            }
        }
    }

    companion object {
        const val PAGE_NAME = "SftpHomePage"
    }
}

/** SFTP Tab 栏（连接 / 收藏 / 历史） */
internal fun ViewContainer<*, *>.SftpTabBar(currentProvider: () -> Int, onChange: (Int) -> Unit) {
    val tabs = listOf("连接", "收藏", "历史")
    View {
        attr {
            flexDirectionRow()
            size(pagerData.pageViewWidth, 44f)
            backgroundColor(SftpColorTokens.cardBg)
        }
        tabs.forEachIndexed { index, label ->
            View {
                attr {
                    flex(1f)
                    height(44f)
                    allCenter()
                }
                event {
                    click { onChange(index) }
                }
                Text {
                    attr {
                        text(label)
                        fontSize(15f)
                        // 在 attr 内读取，依赖才会被收集，切换 Tab 才会刷新高亮
                        if (currentProvider() == index) {
                            color(SftpColorTokens.primary)
                            fontWeightBold()
                        } else {
                            color(SftpColorTokens.textSecondary)
                            fontWeightNormal()
                        }
                    }
                }
            }
        }
    }
}

/** 加载态 */
internal fun ViewContainer<*, *>.SftpLoadingView() {
    View {
        attr { flex(1f); allCenter() }
        Text {
            attr {
                text(I18n.t("sftp.ui.loading"))
                fontSize(14f)
                color(SftpColorTokens.textSecondary)
            }
        }
    }
}

/** 空态 */
internal fun ViewContainer<*, *>.SftpEmptyView(message: String) {
    View {
        attr { flex(1f); allCenter() }
        Text {
            attr {
                text(message)
                fontSize(14f)
                color(SftpColorTokens.textSecondary)
            }
        }
    }
}

/** 错误态 + 重试按钮 */
internal fun ViewContainer<*, *>.SftpErrorView(message: String, onRetry: () -> Unit) {
    View {
        attr { flex(1f); allCenter(); flexDirectionColumn() }
        Text {
            attr {
                text("${I18n.t("sftp.ui.error")}: $message")
                fontSize(14f)
                color(SftpColorTokens.danger)
                marginBottom(16f)
            }
        }
        View {
            attr {
                size(88f, 32f)
                allCenter()
                backgroundColor(SftpColorTokens.primary)
                borderRadius(16f)
            }
            event { click { onRetry() } }
            Text {
                attr {
                    text(I18n.t("sftp.ui.retry"))
                    fontSize(14f)
                    color(Color.WHITE)
                }
            }
        }
    }
}

/** 连接列表项视图（§17.3.1 线框） */
internal fun ViewContainer<*, *>.SftpConnectionListView(
    connectionsProvider: () -> ObservableList<SftpConnection>,
    onClick: (SftpConnection) -> Unit,
    onDualPane: ((SftpConnection) -> Unit)? = null,
    onTerminal: ((SftpConnection) -> Unit)? = null
) {
    Scroller {
        attr {
            flex(1f)
            width(pagerData.pageViewWidth)
            showScrollerIndicator(true)
            flexDirectionColumn()
            backgroundColor(SftpColorTokens.bg)
        }
        // vfor：列表变化按 diff 更新（否则非空→非空的变化不会重建分支，列表会陈旧）
        vfor(connectionsProvider) { conn ->
            View {
                attr {
                    width(pagerData.pageViewWidth - 8f)   // 预留滚动条宽度
                    padding(16f, 12f, 16f, 12f)
                    backgroundColor(SftpColorTokens.cardBg)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                event { click { onClick(conn) } }
                Text {
                    attr {
                        text(conn.label.ifEmpty { "${conn.host}:${conn.port}" })
                        fontSize(16f)
                        color(SftpColorTokens.textPrimary)
                        flex(1f)
                    }
                }
                Text {
                    attr {
                        text("${conn.user}@${conn.host}:${conn.port}")
                        fontSize(13f)
                        color(SftpColorTokens.textSecondary)
                    }
                }
                if (onDualPane != null) {
                    // 桌面双栏入口：以该连接作为远端栏打开（先有远端，再有双栏）
                    Text {
                        attr {
                            text("⇄")
                            fontSize(18f)
                            color(SftpColorTokens.primary)
                            marginLeft(10f)
                        }
                        event { click { onDualPane(conn) } }
                    }
                }
                if (onTerminal != null) {
                    // 终端入口：以该连接开远程终端（独立窗口）
                    Text {
                        attr {
                            text(">_")
                            fontSize(14f)
                            color(SftpColorTokens.primary)
                            marginLeft(10f)
                        }
                        event { click { onTerminal(conn) } }
                    }
                }
            }
        }
    }
}

/** 收藏列表渲染（可点击进入：目录→浏览，文件→播放） */
internal fun ViewContainer<*, *>.SftpFavoritesList(
    itemsProvider: () -> ObservableList<com.tencent.kuikly.core.module.sftp.SftpFavorite>,
    onClick: (com.tencent.kuikly.core.module.sftp.SftpFavorite) -> Unit
) {
    Scroller {
        attr {
            flex(1f)
            width(pagerData.pageViewWidth)
            showScrollerIndicator(true)
            flexDirectionColumn()
            backgroundColor(SftpColorTokens.bg)
        }
        vfor(itemsProvider) { fav ->
            View {
                attr {
                    width(pagerData.pageViewWidth - 8f)   // 预留滚动条宽度
                    padding(16f, 12f, 16f, 12f)
                    backgroundColor(SftpColorTokens.cardBg)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                event { click { onClick(fav) } }
                Text {
                    attr {
                        text((if (fav.isDir) "📁 " else "📄 ") + fav.name)
                        fontSize(15f)
                        color(SftpColorTokens.textPrimary)
                        flex(1f)
                    }
                }
                Text {
                    attr {
                        text(fav.connectionLabel)
                        fontSize(12f)
                        color(SftpColorTokens.textSecondary)
                    }
                }
            }
        }
    }
}

/** 历史列表渲染（可点击进入播放页） */
internal fun ViewContainer<*, *>.SftpHistoryList(
    itemsProvider: () -> ObservableList<com.tencent.kuikly.core.module.sftp.SftpPlaybackRecord>,
    onClick: (com.tencent.kuikly.core.module.sftp.SftpPlaybackRecord) -> Unit,
    onDelete: ((com.tencent.kuikly.core.module.sftp.SftpPlaybackRecord) -> Unit)? = null
) {
    Scroller {
        attr {
            flex(1f)
            width(pagerData.pageViewWidth)
            showScrollerIndicator(true)
            flexDirectionColumn()
            backgroundColor(SftpColorTokens.bg)
        }
        vfor(itemsProvider) { rec ->
            View {
                attr {
                    width(pagerData.pageViewWidth - 8f)   // 预留滚动条宽度
                    padding(16f, 12f, 16f, 12f)
                    backgroundColor(SftpColorTokens.cardBg)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                event { click { onClick(rec) } }
                Text {
                    attr {
                        text("🎬 " + rec.name)
                        fontSize(15f)
                        color(SftpColorTokens.textPrimary)
                        flex(1f)
                    }
                }
                Text {
                    attr {
                        text(formatDuration(rec.position))
                        fontSize(12f)
                        color(SftpColorTokens.textSecondary)
                    }
                }
                if (onDelete != null) {
                    // 每条历史记录右侧的删除按钮（独立格：避免嵌套点击冒泡到行）
                    View {
                        attr {
                            width(34f)
                            height(34f)
                            allCenter()
                            accessibility("history_delete_btn")
                        }
                        event { click { onDelete(rec) } }
                        Text { attr { text("✕"); fontSize(14f); color(SftpColorTokens.danger) } }
                    }
                }
            }
        }
    }
}

/** 格式化时长 mm:ss 或 HH:mm:ss */
internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
}

/** 首页默认入口：本地文件管理（双栏）；仅 Web/桌面注入。 */
internal fun ViewContainer<*, *>.SftpLocalFileManagerEntry(
    onClick: () -> Unit,
    onTerminal: (() -> Unit)? = null,
    fillWidth: Boolean = true,
) {
    View {
        attr {
            if (fillWidth) width(pagerData.pageViewWidth - 8f) else flex(1f)
            padding(16f, 14f, 16f, 14f)
            backgroundColor(SftpColorTokens.cardBg)
            flexDirectionRow()
            alignItemsCenter()
        }
        event { click { onClick() } }
        Text { attr { text("\uD83D\uDCBB"); fontSize(20f) } }
        View {
            attr { flex(1f); flexDirectionColumn(); marginLeft(12f) }
            Text { attr { text("本地文件管理"); fontSize(16f); color(SftpColorTokens.textPrimary) } }
            Text {
                attr {
                    text("浏览本机文件；右侧可切换远端主机并双向传输")
                    fontSize(12f); color(SftpColorTokens.textSecondary); marginTop(2f)
                }
            }
        }
        Text { attr { text("›"); fontSize(20f); color(SftpColorTokens.textSecondary) } }
    }
}

/** 「更多」抽屉里的一行 */
private fun ViewContainer<*, *>.MoreRow(title: String, subtitle: String, onClick: () -> Unit) {
    View {
        attr {
            width(pagerData.pageViewWidth - 32f)
            padding(14f, 10f, 14f, 10f)
            flexDirectionColumn()
        }
        event { click { onClick() } }
        Text { attr { text(title); fontSize(15f); color(SftpColorTokens.textPrimary) } }
        Text { attr { text(subtitle); fontSize(12f); color(SftpColorTokens.textSecondary); marginTop(2f) } }
    }
}
