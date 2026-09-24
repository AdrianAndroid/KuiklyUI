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
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.views.Scroller
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
    // 必须可观察：异步回调改了状态要能触发重渲染。
    // entries 用 ObservableList，配合 vfor 才能按 diff 增删行（普通 List 在
    // vif 分支条件不变时不会重建，切目录后列表会一直是旧的）。
    private var entries by observableList<SftpEntry>()
    private var loading: Boolean by observable(true)
    private var errorMsg: String? by observable(null)
    private var currentPath: String by observable("/")

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
                entries.clear()
                // 按名称从小到大排序（不区分大小写；同名再按原串，保证稳定）
                entries.addAll(items.sortedWith(compareBy({ it.name.lowercase() }, { it.name })))
                loading = false
                errorMsg = null
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
                // 右上角：切到双栏（本地 ↔ 当前远端目录）—— 仅 Web/桌面
                vif({ ctx.isWebLike }) {
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
                    SftpEntriesView({ ctx.entries }) { entry -> ctx.onEntryClick(entry) }
                }
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
            // 桌面壳会开独立窗口（可同时播多个），其它端页内路由
            openPlayerPage(params)
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
    entriesProvider: () -> ObservableList<SftpEntry>,
    onClick: (SftpEntry) -> Unit
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
