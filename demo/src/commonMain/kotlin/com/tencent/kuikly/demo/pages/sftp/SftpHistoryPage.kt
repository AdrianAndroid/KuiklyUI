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
import com.tencent.kuikly.core.module.sftp.SftpPlaybackRecord
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.demo.pages.sftp.theme.SftpAccessibility
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * SFTP 播放历史页（§20.9）
 *
 * - 列表展示历史，按 lastPlayedAt 倒序
 * - 显示进度条（progressPercent）
 * - 已完成项显示「已看完」标签
 * - 顶部「清空全部」按钮（按 connectionId 清空）
 * - 左滑/长按删除单条
 * - 点击跳播放页（带 resume 参数）
 */
@Page(SftpHistoryPage.PAGE_NAME)
internal class SftpHistoryPage : SftpBasePager() {

    private var records: List<SftpPlaybackRecord> = emptyList()
    private var loading: Boolean = true
    private var errorMsg: String? = null
    private var currentConnectionId: String? = null

    override fun created() {
        super.created()
        currentConnectionId = pageData.params.optString("connectionId", "").takeIf { it.isNotEmpty() }
        refresh()
    }

    private fun refresh() {
        loading = true
        errorMsg = null
        val connId = currentConnectionId
        if (connId != null) {
            sftpPlaybackHistoryModule().listByConnection(connId) { result, err ->
                if (err != null) {
                    errorMsg = err.msg
                    loading = false
                } else {
                    records = result.sortedByDescending { it.lastPlayedAt }
                    loading = false
                }
            }
        } else {
            // 无 connectionId 时展示空（Phase 5 接入「所有连接」聚合）
            loading = false
            records = emptyList()
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
                    event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
                    Text { attr { text("<"); fontSize(22f); color(SftpColorTokens.textPrimary) } }
                }
                Text {
                    attr {
                        text(I18n.t("sftp.history.title"))
                        fontSize(18f)
                        fontWeightBold()
                        color(SftpColorTokens.textPrimary)
                        flex(1f)
                        marginLeft(8f)
                    }
                }
                if (ctx.records.isNotEmpty() && ctx.currentConnectionId != null) {
                    View {
                        attr { height(32f); padding(8f, 12f, 8f, 12f); allCenter(); accessibility(SftpAccessibility.BTN_DELETE) }
                        event { click { ctx.clearAll() } }
                        Text { attr { text(I18n.t("sftp.history.clear_all")); fontSize(13f); color(SftpColorTokens.danger) } }
                    }
                }
            }

            // 内容区
            View {
                attr { flex(1f) }
                when {
                    ctx.loading -> SftpLoadingView()
                    ctx.errorMsg != null -> SftpErrorView(ctx.errorMsg!!) { ctx.refresh() }
                    ctx.records.isEmpty() -> SftpEmptyView(I18n.t("sftp.history.empty"))
                    else -> SftpHistoryListView(ctx.records) { record -> ctx.onRecordClick(record) }
                }
            }
        }
    }

    private fun onRecordClick(record: SftpPlaybackRecord) {
        val params = com.tencent.kuikly.core.nvi.serialization.json.JSONObject()
        params.put("connectionId", record.connectionId)
        params.put("connectionLabel", record.connectionLabel)
        params.put("remotePath", record.remotePath)
        params.put("name", record.name)
        params.put("size", record.size)
        openPlayerPage(params)
    }

    private fun clearAll() {
        currentConnectionId?.let { connId ->
            sftpPlaybackHistoryModule().clearByConnection(connId) { _, _ -> refresh() }
        }
    }

    companion object {
        const val PAGE_NAME = "SftpHistoryPage"
    }
}

/** 历史列表视图（带进度条） */
internal fun ViewContainer<*, *>.SftpHistoryListView(
    records: List<SftpPlaybackRecord>,
    onClick: (SftpPlaybackRecord) -> Unit
) {
    View {
        attr { flex(1f); backgroundColor(SftpColorTokens.bg) }
        records.forEach { record ->
            View {
                attr {
                    width(pagerData.pageViewWidth)
                    padding(16f, 12f, 16f, 12f)
                    backgroundColor(SftpColorTokens.cardBg)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                event { click { onClick(record) } }
                // 缩略图占位
                View {
                    attr {
                        size(48f, 48f)
                        backgroundColor(SftpColorTokens.divider)
                        borderRadius(8f)
                        allCenter()
                    }
                    Text { attr { text("▶"); fontSize(16f); color(SftpColorTokens.textSecondary) } }
                }
                View {
                    attr { flex(1f); flexDirectionColumn(); marginLeft(12f) }
                    Text {
                        attr {
                            text(record.name)
                            fontSize(15f)
                            color(SftpColorTokens.textPrimary)
                        }
                    }
                    Text {
                        attr {
                            text(record.connectionLabel + " · " + formatTime(record.position) + "/" + formatTime(record.duration))
                            fontSize(12f)
                            color(SftpColorTokens.textSecondary)
                            marginTop(4f)
                        }
                    }
                    // 进度条
                    View {
                        attr {
                            width(pagerData.pageViewWidth - 100f)
                            height(3f)
                            backgroundColor(SftpColorTokens.divider)
                            borderRadius(2f)
                            marginTop(6f)
                        }
                        View {
                            attr {
                                width((pagerData.pageViewWidth - 100f) * record.progressPercent / 100f)
                                height(3f)
                                backgroundColor(SftpColorTokens.primary)
                                borderRadius(2f)
                            }
                        }
                    }
                    if (record.completed) {
                        Text {
                            attr {
                                text(I18n.t("sftp.history.completed"))
                                fontSize(11f)
                                color(SftpColorTokens.primary)
                                marginTop(2f)
                            }
                        }
                    }
                }
            }
        }
    }
}
