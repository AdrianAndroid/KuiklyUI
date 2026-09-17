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
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.sftp.MimeExtMap
import com.tencent.kuikly.core.module.sftp.SftpConnectParam
import com.tencent.kuikly.core.module.sftp.SftpEntry
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
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
    private var currentPath: String = "/"
    private var entries: List<SftpEntry> = emptyList()
    private var loading: Boolean = true
    private var errorMsg: String? = null

    override fun created() {
        super.created()
        val params = pageData.params
        connectParam = SftpConnectParam.fromJson(params)
        connectionId = params.optString("connectionId", "")
        connectionLabel = params.optString("connectionLabel", "")
        currentPath = params.optString("remotePath", "/")
        doConnectAndList()
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
                errorMsg = err.msg
                loading = false
            } else {
                entries = items
                loading = false
                errorMsg = null
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
                            ctx.sessionId?.let { sid ->
                                ctx.sftpModule().disconnect(sid) {
                                    ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                                }
                            } ?: ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                        }
                    }
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
            }

            // 内容区三态
            View {
                attr { flex(1f) }
                when {
                    ctx.loading -> SftpLoadingView()
                    ctx.errorMsg != null -> SftpErrorView(ctx.errorMsg!!) { ctx.doConnectAndList() }
                    ctx.entries.isEmpty() -> SftpEmptyView("空目录")
                    else -> SftpEntriesView(ctx.entries) { entry -> ctx.onEntryClick(entry) }
                }
            }
        }
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
        if (MimeExtMap.isVideo(mime) || MimeExtMap.isAudio(mime)) {
            // 视频或音频 → 播放页（§17.3.3 / §20）
            router.openPage(SftpPlayerPage.PAGE_NAME, params)
        } else {
            // 其他类型 → 预览分发页（§4.1 / §17.3.3.3）
            router.openPage(SftpViewerDispatcherPage.PAGE_NAME, params)
        }
    }

    companion object {
        const val PAGE_NAME = "SftpBrowserPage"
    }
}

/** 目录项列表渲染 */
internal fun ViewContainer<*, *>.SftpEntriesView(
    entries: List<SftpEntry>,
    onClick: (SftpEntry) -> Unit
) {
    View {
        attr { flex(1f); backgroundColor(SftpColorTokens.bg) }
        entries.forEach { entry ->
            View {
                attr {
                    width(pagerData.pageViewWidth)
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
