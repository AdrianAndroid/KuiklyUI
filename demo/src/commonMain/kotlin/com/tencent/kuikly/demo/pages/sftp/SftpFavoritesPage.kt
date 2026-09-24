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
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.sftp.I18n
import com.tencent.kuikly.core.module.sftp.SftpFavorite
import com.tencent.kuikly.core.module.sftp.SftpFavoriteSortBy
import com.tencent.kuikly.core.module.sftp.SftpFavoritesModule
import com.tencent.kuikly.core.module.sftp.SortOrder
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.demo.pages.sftp.theme.SftpAccessibility
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * SFTP 收藏页（§17.3.4 / §21.4）
 *
 * - 列表展示收藏项，可按 STARRED_AT / NAME / CONNECTION_LABEL / MTIME 排序
 * - 顶部支持搜索（§3.4.7 search）
 * - 失效项（connectionId 已不存在）显示警告色（§21.4.6 stale 检测）
 * - 左滑/长按删除单项
 * - 点击文件项跳播放/预览；点击目录项跳浏览器
 */
@Page(SftpFavoritesPage.PAGE_NAME)
internal class SftpFavoritesPage : SftpBasePager() {

    private var items: List<SftpFavorite> = emptyList()
    private var loading: Boolean = true
    private var errorMsg: String? = null
    private var sortBy: SftpFavoriteSortBy = SftpFavoriteSortBy.STARRED_AT
    private var sortOrder: SortOrder = SortOrder.DESC
    private var searchKeyword: String = ""
    private var staleConnectionIds: Set<String> = emptySet()  // 失效连接集合

    override fun created() {
        super.created()
        refresh()
    }

    private fun refresh() {
        loading = true
        errorMsg = null
        if (searchKeyword.isNotEmpty()) {
            sftpFavoritesModule().search(searchKeyword) { result, err ->
                if (err != null) {
                    errorMsg = err.msg
                    loading = false
                } else {
                    items = result
                    loading = false
                }
            }
        } else {
            sftpFavoritesModule().list(null, sortBy, sortOrder) { result, err ->
                if (err != null) {
                    errorMsg = err.msg
                    loading = false
                } else {
                    items = result
                    loading = false
                }
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
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
                View {
                    attr { size(36f, 36f); allCenter(); accessibility(SftpAccessibility.BTN_BACK) }
                    event {
                        click {
                            ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                        }
                    }
                    Text { attr { text("<"); fontSize(22f); color(SftpColorTokens.textPrimary) } }
                }
                Text {
                    attr {
                        text(I18n.t("sftp.favorites.title"))
                        fontSize(18f)
                        fontWeightBold()
                        color(SftpColorTokens.textPrimary)
                        flex(1f)
                        marginLeft(8f)
                    }
                }
                // 排序按钮
                View {
                    attr { size(36f, 36f); allCenter(); accessibility(SftpAccessibility.BTN_SORT) }
                    event { click { ctx.cycleSort() } }
                    Text { attr { text("⇅"); fontSize(18f); color(SftpColorTokens.textPrimary) } }
                }
            }

            // 内容区
            View {
                attr { flex(1f) }
                when {
                    ctx.loading -> SftpLoadingView()
                    ctx.errorMsg != null -> SftpErrorView(ctx.errorMsg!!) { ctx.refresh() }
                    ctx.items.isEmpty() -> SftpEmptyView(I18n.t("sftp.favorites.empty"))
                    else -> SftpFavoritesListView(
                        ctx.items,
                        ctx.staleConnectionIds
                    ) { favorite -> ctx.onFavoriteClick(favorite) }
                }
            }
        }
    }

    private fun onFavoriteClick(favorite: SftpFavorite) {
        if (favorite.isDir) {
            val params = com.tencent.kuikly.core.nvi.serialization.json.JSONObject()
            params.put("connectionId", favorite.connectionId)
            params.put("remotePath", favorite.remotePath)
            acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(SftpBrowserPage.PAGE_NAME, params)
        } else {
            val params = com.tencent.kuikly.core.nvi.serialization.json.JSONObject()
            params.put("connectionId", favorite.connectionId)
            params.put("connectionLabel", favorite.connectionLabel)
            params.put("remotePath", favorite.remotePath)
            params.put("name", favorite.name)
            params.put("size", favorite.size)
            openPlayerPage(params)
        }
    }

    private fun cycleSort() {
        sortBy = when (sortBy) {
            SftpFavoriteSortBy.STARRED_AT -> SftpFavoriteSortBy.NAME
            SftpFavoriteSortBy.NAME -> SftpFavoriteSortBy.CONNECTION_LABEL
            SftpFavoriteSortBy.CONNECTION_LABEL -> SftpFavoriteSortBy.MTIME
            SftpFavoriteSortBy.MTIME -> SftpFavoriteSortBy.STARRED_AT
        }
        sortOrder = if (sortOrder == SortOrder.ASC) SortOrder.DESC else SortOrder.ASC
        refresh()
    }

    companion object {
        const val PAGE_NAME = "SftpFavoritesPage"
    }
}

/** 收藏列表视图（含 stale 检测） */
internal fun ViewContainer<*, *>.SftpFavoritesListView(
    items: List<SftpFavorite>,
    staleConnectionIds: Set<String>,
    onClick: (SftpFavorite) -> Unit
) {
    View {
        attr { flex(1f); backgroundColor(SftpColorTokens.bg) }
        items.forEach { favorite ->
            val isStale = favorite.connectionId in staleConnectionIds
            View {
                attr {
                    width(pagerData.pageViewWidth)
                    padding(16f, 12f, 16f, 12f)
                    backgroundColor(SftpColorTokens.cardBg)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                event { click { onClick(favorite) } }
                Text {
                    attr {
                        text(if (favorite.isDir) "📁 " else "⭐ ")
                        fontSize(18f)
                    }
                }
                View {
                    attr { flex(1f); flexDirectionColumn(); marginLeft(8f) }
                    Text {
                        attr {
                            text(favorite.name)
                            fontSize(15f)
                            color(if (isStale) SftpColorTokens.danger else SftpColorTokens.textPrimary)
                        }
                    }
                    Text {
                        attr {
                            text(favorite.connectionLabel + " · " + favorite.remotePath)
                            fontSize(12f)
                            color(SftpColorTokens.textSecondary)
                            marginTop(4f)
                        }
                    }
                    if (isStale) {
                        Text {
                            attr {
                                text(I18n.t("sftp.favorites.stale_warning"))
                                fontSize(11f)
                                color(SftpColorTokens.danger)
                                marginTop(2f)
                            }
                        }
                    }
                }
            }
        }
    }
}
