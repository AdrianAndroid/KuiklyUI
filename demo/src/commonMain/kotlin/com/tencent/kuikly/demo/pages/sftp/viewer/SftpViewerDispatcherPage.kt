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
package com.tencent.kuikly.demo.pages.sftp.viewer

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.sftp.EncodingDetector
import com.tencent.kuikly.core.module.sftp.I18n
import com.tencent.kuikly.core.module.sftp.SftpMediaProxyModule
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.module.sftp.SftpMediaUrlBuilder
import com.tencent.kuikly.core.module.sftp.MimeExtMap
import com.tencent.kuikly.core.module.sftp.SftpModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.SftpBasePager
import com.tencent.kuikly.demo.pages.sftp.SftpBrowserPage
import com.tencent.kuikly.demo.pages.sftp.SftpEmptyView
import com.tencent.kuikly.demo.pages.sftp.SftpErrorView
import com.tencent.kuikly.demo.pages.sftp.SftpLoadingView
import com.tencent.kuikly.demo.pages.sftp.theme.SftpAccessibility
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * 预览分发页（§4 / §17.3.3）
 *
 * 输入：路由参数 `sessionId / remotePath / name / size / connectionId / connectionLabel`。
 * 根据 [MimeExtMap.mimeOfPath] 决定打开哪种预览：
 * - text/markdown/html/image/pdf → 对应预览页
 * - audio/video → 页面异步申请本地代理 token 后把 URL 传给渲染组件
 * - 其他 → 提示「不支持预览」
 *
 * 预览采用「先 openRead 拉头部 ~8KB → 判断编码 / MIME → 选 viewer」的策略（§4.1）。
 */
@Page(SftpViewerDispatcherPage.PAGE_NAME)
internal class SftpViewerDispatcherPage : SftpBasePager() {

    private var sessionId: String = ""
    private var remotePath: String = ""
    private var name: String = ""
    private var size: Long = 0L
    private var connectionId: String = ""
    private var connectionLabel: String = ""

    private var loading: Boolean by observable(true)
    private var errorMsg: String? by observable(null)
    /** 本地代理播放地址；图片/音频预览靠它渲染（由本页异步申请，组件只读） */
    private var mediaUrl: String? by observable(null)
    private var mime: String = ""
    private var encoding: String = "UTF-8"
    private var detectedHead: ByteArray? = null
    private var viewer: ViewerKind = ViewerKind.UNSUPPORTED

    enum class ViewerKind {
        TEXT, MARKDOWN, HTML, IMAGE, PDF, AUDIO, VIDEO, UNSUPPORTED
    }

    override fun pageWillDestroy() {
        super.pageWillDestroy()
        // 释放代理 token（若有申请过）
        mediaToken?.let { tk -> sftpMediaProxyModule().unregisterToken(tk) }
    }

    private var mediaToken: String? = null

    override fun created() {
        super.created()
        val params = pageData.params
        sessionId = params.optString("sessionId", "")
        remotePath = params.optString("remotePath", "")
        name = params.optString("name", "")
        size = params.optLong("size", 0L)
        connectionId = params.optString("connectionId", "")
        connectionLabel = params.optString("connectionLabel", "")
        detectAndDispatch()
    }

    private fun detectAndDispatch() {
        // 1. 用扩展名 + MIME 决定 viewer
        mime = MimeExtMap.mimeOfPath(remotePath)
        viewer = decideViewer(mime)

        // 2. 如果是文本类，先拉头部 8KB 检测编码（§4.2 / §21.4.7）
        if (viewer == ViewerKind.TEXT || viewer == ViewerKind.MARKDOWN || viewer == ViewerKind.HTML) {
            fetchHeadAndDetect()
        } else {
            loading = false
        }

        // 3. 图片/音频需要本地代理地址：副作用在页面层异步完成
        if (viewer == ViewerKind.IMAGE || viewer == ViewerKind.AUDIO) {
            resolveMediaUrl()
        }
    }

    private fun decideViewer(mime: String): ViewerKind {
        return when {
            MimeExtMap.isMarkdown(remotePath) -> ViewerKind.MARKDOWN
            MimeExtMap.isHtml(remotePath) -> ViewerKind.HTML
            MimeExtMap.isImage(mime) -> ViewerKind.IMAGE
            MimeExtMap.isPdf(mime) -> ViewerKind.PDF
            MimeExtMap.isAudio(mime) -> ViewerKind.AUDIO
            MimeExtMap.isVideo(mime) -> ViewerKind.VIDEO
            MimeExtMap.isText(mime) -> ViewerKind.TEXT
            else -> ViewerKind.UNSUPPORTED
        }
    }

    /**
     * 申请本地代理端口 + token，拼出播放地址。
     * 副作用放在页面层（Pager）而不是渲染组件里：组件在 body() 中同步调代理拿不到
     * 真实 token（旧实现就是坏的），而且违反分层。
     */
    private fun resolveMediaUrl() {
        val proxy = sftpMediaProxyModule()
        val sid = sessionId.ifEmpty { connectionId }
        proxy.startOrGetPort { port ->
            if (port <= 0) {
                errorMsg = I18n.t("sftp.error.proxy_start_failed")
                return@startOrGetPort
            }
            proxy.registerToken(sid, remotePath, size) { tk ->
                if (tk.isEmpty()) {
                    errorMsg = I18n.t("sftp.error.proxy_read_failed")
                } else {
                    mediaToken = tk
                    mediaUrl = SftpMediaUrlBuilder.buildPlayUrl(port, tk, name)
                }
            }
        }
    }

    private fun fetchHeadAndDetect() {
        // 1. openRead → read 8KB → close → detect encoding
        sftpModule().openRead(sessionId, remotePath) { fileHandleId, err ->
            if (err != null || fileHandleId == null) {
                errorMsg = err?.msg ?: "openRead 失败"
                loading = false
                return@openRead
            }
            sftpModule().read(fileHandleId, 0L, 8192) { bytes, readErr ->
                if (readErr != null || bytes == null) {
                    errorMsg = readErr?.msg ?: "read 失败"
                    loading = false
                    sftpModule().close(fileHandleId) {}
                    return@read
                }
                detectedHead = bytes
                encoding = EncodingDetector.detect(bytes)
                loading = false
                sftpModule().close(fileHandleId) {}
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
                        text(ctx.name)
                        fontSize(15f)
                        fontWeightBold()
                        color(SftpColorTokens.textPrimary)
                        flex(1f)
                        marginLeft(8f)
                    }
                }
            }

            // 内容区
            View {
                attr { flex(1f) }
                when {
                    ctx.loading -> SftpLoadingView()
                    ctx.errorMsg != null -> SftpErrorView(ctx.errorMsg!!) { ctx.detectAndDispatch() }
                    else -> {
                        // 根据 viewer 渲染对应预览
                        when (ctx.viewer) {
                            ViewerKind.TEXT -> SftpTextViewer(ctx.sessionId, ctx.remotePath, ctx.size, ctx.encoding, ctx.detectedHead)
                            ViewerKind.MARKDOWN -> SftpMarkdownViewer(ctx.sessionId, ctx.remotePath, ctx.size, ctx.encoding)
                            ViewerKind.HTML -> SftpHtmlViewer(ctx.sessionId, ctx.remotePath, ctx.size)
                            ViewerKind.IMAGE -> SftpImageViewer(
                                mediaUrlProvider = { ctx.mediaUrl },
                                fileNameProvider = { ctx.name }
                            )
                            ViewerKind.PDF -> SftpPdfViewer(ctx.connectionId, ctx.remotePath, ctx.name, ctx.size)
                            ViewerKind.AUDIO -> SftpAudioViewer(
                                mediaUrlProvider = { ctx.mediaUrl },
                                fileNameProvider = { ctx.name }
                            )
                            ViewerKind.VIDEO -> {
                                // 跳到 SftpPlayerPage（Phase 0.3 已实现）
                                ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                                SftpEmptyView("跳转播放页...")
                            }
                            ViewerKind.UNSUPPORTED -> SftpEmptyView(I18n.t("sftp.viewer.unsupported"))
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val PAGE_NAME = "SftpViewerDispatcherPage"
    }
}
