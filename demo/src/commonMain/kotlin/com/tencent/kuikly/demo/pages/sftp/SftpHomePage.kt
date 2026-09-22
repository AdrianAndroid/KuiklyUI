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
                velseif({ ctx.currentTab == 0 && ctx.connections.isEmpty() }) {
                    SftpEmptyView("暂无连接，点 + 新建")
                }
                velseif({ ctx.currentTab == 0 }) {
                    SftpConnectionListView({ ctx.connections }) { conn -> ctx.openBrowser(conn) }
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
                    SftpHistoryList({ ctx.history }) { rec -> ctx.openHistory(rec) }
                }
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
            acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                .openPage(SftpPlayerPage.PAGE_NAME, params)
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
        acquireModule<RouterModule>(RouterModule.MODULE_NAME)
            .openPage(SftpPlayerPage.PAGE_NAME, params)
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
    onClick: (SftpConnection) -> Unit
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
    onClick: (com.tencent.kuikly.core.module.sftp.SftpPlaybackRecord) -> Unit
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
