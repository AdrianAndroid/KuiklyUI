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
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.sftp.I18n
import com.tencent.kuikly.core.module.sftp.SftpConnection
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
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

    private var connections: List<SftpConnection> = emptyList()
    private var loading: Boolean = true
    private var errorMsg: String? = null
    private var currentTab: Int = 0  // 0 连接 / 1 收藏 / 2 历史
    // 收藏 Tab 状态
    internal var favorites: List<com.tencent.kuikly.core.module.sftp.SftpFavorite> = emptyList()
    internal var favoritesLoaded: Boolean = false
    internal var favoritesError: String? = null
    // 历史 Tab 状态
    internal var history: List<com.tencent.kuikly.core.module.sftp.SftpPlaybackRecord> = emptyList()
    internal var historyLoaded: Boolean = false
    internal var historyError: String? = null

    override fun body(): ViewBuilder {
        val ctx = this
        return {
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
            SftpTabBar(ctx.currentTab) { newTab -> ctx.onTabChange(newTab) }

            // 内容区（三态：loading / empty / error / list）
            View {
                attr { flex(1f) }
                when {
                    ctx.loading -> SftpLoadingView()
                    ctx.errorMsg != null -> SftpErrorView(ctx.errorMsg!!) { ctx.refresh() }
                    ctx.currentTab == 0 && ctx.connections.isEmpty() ->
                        SftpEmptyView("暂无连接，点 + 新建")
                    ctx.currentTab == 0 ->
                        SftpConnectionListView(ctx.connections) { conn -> ctx.openBrowser(conn) }
                    ctx.currentTab == 1 -> SftpFavoritesTab(ctx.favorites, ctx.favoritesError) {
                        ctx.favoritesLoaded = false
                        ctx.favoritesError = null
                        ctx.sftpFavoritesModule().list { items, error ->
                            ctx.favorites = items
                            ctx.favoritesError = error?.msg
                        }
                    }
                    ctx.currentTab == 2 -> SftpHistoryTab(ctx.history, ctx.historyError) {
                        ctx.historyLoaded = false
                        ctx.historyError = null
                        ctx.sftpPlaybackHistoryModule().listByConnection("") { items, error ->
                            ctx.history = items
                            ctx.historyError = error?.msg
                        }
                    }
                }
            }
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

    private fun onTabChange(newTab: Int) {
        currentTab = newTab
        when (newTab) {
            1 -> if (!favoritesLoaded) {
                favoritesLoaded = true
                sftpFavoritesModule().list { items, error ->
                    favorites = items
                    favoritesError = error?.msg
                }
            }
            2 -> if (!historyLoaded) {
                historyLoaded = true
                sftpPlaybackHistoryModule().listByConnection("") { items, error ->
                    history = items
                    historyError = error?.msg
                }
            }
        }
    }

    override fun created() {
        super.created()
        refresh()
    }

    private fun refresh() {
        loading = true
        errorMsg = null
        sftpConnectionModule().list { items, error ->
            loading = false
            if (error != null) {
                errorMsg = error.msg
            } else {
                connections = items
            }
        }
    }

    companion object {
        const val PAGE_NAME = "SftpHomePage"
    }
}

/** SFTP Tab 栏（连接 / 收藏 / 历史） */
internal fun ViewContainer<*, *>.SftpTabBar(current: Int, onChange: (Int) -> Unit) {
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
                        color(if (current == index) SftpColorTokens.primary else SftpColorTokens.textSecondary)
                        if (current == index) fontWeightBold() else fontWeightNormal()
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
    connections: List<SftpConnection>,
    onClick: (SftpConnection) -> Unit
) {
    View {
        attr { flex(1f); backgroundColor(SftpColorTokens.bg) }
        connections.forEach { conn ->
            View {
                attr {
                    width(pagerData.pageViewWidth)
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

/** 收藏 Tab：加载 [SftpFavoritesModule.list] 并展示（§17.3.4） */
internal fun ViewContainer<*, *>.SftpFavoritesTab(
    favorites: List<com.tencent.kuikly.core.module.sftp.SftpFavorite>,
    error: String?,
    onRetry: () -> Unit
) {
    when {
        error != null -> SftpErrorView(error) { onRetry() }
        favorites.isEmpty() -> SftpEmptyView("暂无收藏")
        else -> SftpFavoritesList(favorites)
    }
}

/** 历史 Tab：加载 [SftpPlaybackHistoryModule.listByConnection] 并展示（§20.9） */
internal fun ViewContainer<*, *>.SftpHistoryTab(
    history: List<com.tencent.kuikly.core.module.sftp.SftpPlaybackRecord>,
    error: String?,
    onRetry: () -> Unit
) {
    when {
        error != null -> SftpErrorView(error) { onRetry() }
        history.isEmpty() -> SftpEmptyView("暂无播放历史")
        else -> SftpHistoryList(history)
    }
}

/** 收藏列表渲染 */
internal fun ViewContainer<*, *>.SftpFavoritesList(items: List<com.tencent.kuikly.core.module.sftp.SftpFavorite>) {
    View {
        attr { flex(1f); backgroundColor(SftpColorTokens.bg) }
        items.forEach { fav ->
            View {
                attr {
                    width(pagerData.pageViewWidth)
                    padding(16f, 12f, 16f, 12f)
                    backgroundColor(SftpColorTokens.cardBg)
                    flexDirectionRow()
                    alignItemsCenter()
                }
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

/** 历史列表渲染 */
internal fun ViewContainer<*, *>.SftpHistoryList(items: List<com.tencent.kuikly.core.module.sftp.SftpPlaybackRecord>) {
    View {
        attr { flex(1f); backgroundColor(SftpColorTokens.bg) }
        items.forEach { rec ->
            View {
                attr {
                    width(pagerData.pageViewWidth)
                    padding(16f, 12f, 16f, 12f)
                    backgroundColor(SftpColorTokens.cardBg)
                    flexDirectionRow()
                    alignItemsCenter()
                }
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
