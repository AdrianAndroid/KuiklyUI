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
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.demo.pages.sftp.viewer.md.MarkdownParser
import com.tencent.kuikly.demo.pages.sftp.viewer.md.MdBlock
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

    // ---- 阅读器状态（文本类）----
    /** 全文（受 SftpTextLoader.MAX_BYTES 限制） */
    private var textContent: String by observable("")
    private var textTruncated: Boolean by observable(false)
    private var mdBlocks: List<MdBlock> by observable(emptyList())
    private var mdOutline: List<Pair<Int, String>> by observable(emptyList())
    private var textLines: List<String> by observable(emptyList())
    private var mdSourceView: Boolean by observable(false)
    private var wrapLines: Boolean by observable(true)
    private var fontScale: Float by observable(1f)
    private var tocVisible: Boolean by observable(false)
    private var readerScrollTo: ((Float) -> Unit)? = null

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

        // 2. 文本类：装载全文（分块流式 + 跨端解码）
        if (viewer == ViewerKind.TEXT || viewer == ViewerKind.MARKDOWN || viewer == ViewerKind.HTML) {
            loadText()
        } else {
            loading = false
        }

        // 3. 图片/音频需要本地代理地址：副作用在页面层异步完成
        if (viewer == ViewerKind.IMAGE || viewer == ViewerKind.AUDIO) {
            resolveMediaUrl()
        }
    }

    private fun decideViewer(mime: String): ViewerKind {
        // 注意：isMarkdown/isHtml 期望的是 **mime**，此前误传 remotePath（路径）→ 恒 false，
        // 导致 .md 一直被当纯文本渲染（Markdown 渲染从未生效）
        return when {
            MimeExtMap.isMarkdown(mime) -> ViewerKind.MARKDOWN
            MimeExtMap.isHtml(mime) -> ViewerKind.HTML
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

    /** 装载全文 → 解析（Markdown 出块与目录；纯文本按行拆分） */
    private fun loadText() {
        loading = true
        SftpTextLoader.load(sftpModule(), sessionId, remotePath, size) { content, loadErr ->
            loading = false
            if (content == null) {
                errorMsg = loadErr ?: "读取失败"
                return@load
            }
            encoding = content.encoding
            textContent = content.text
            textTruncated = content.truncated
            val body = content.text
            if (viewer == ViewerKind.MARKDOWN) {
                // 解析异常兜底：任何解析器问题都退化为纯文本，绝不出现空白文档
                val blocks = try {
                    MarkdownParser.parse(body)
                } catch (e: Throwable) {
                    emptyList()
                }
                val capped = if (blocks.size > MAX_MD_BLOCKS) blocks.subList(0, MAX_MD_BLOCKS) else blocks
                mdBlocks = capped
                mdOutline = try { MarkdownParser.outline(capped) } catch (e: Throwable) { emptyList() }
                textLines = body.split('\n')
                if (capped.isEmpty()) mdSourceView = true   // 解析失败 → 直接显示源码，避免空白

            } else {
                textLines = body.split('\n')
            }
        }
    }

    private fun zoom(delta: Float) {
        fontScale = (fontScale + delta).coerceIn(0.7f, 2.2f)
    }

    private fun jumpToOutline(index: Int) {
        tocVisible = false
        // 按标题在块列表中的位置估算滚动偏移（块高不固定，故为近似定位）
        val headingTexts = mdOutline.take(index).map { it.second }.toSet()
        var blocksBefore = 0
        mdBlocks.forEach { b ->
            if (b is MdBlock.Heading && b.text in headingTexts) blocksBefore++
        }
        val estimated = blocksBefore * 46f + blocksBefore * 12f
        readerScrollTo?.invoke(estimated)
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

            // Markdown 目录抽屉（绝对定位；点击条目近似跳转）
            vif({ ctx.tocVisible && ctx.mdOutline.isNotEmpty() }) {
                View {
                    attr {
                        positionAbsolute(); left(0f); top(56f)
                        width(pagerData.pageViewWidth)
                        height(pagerData.pageViewHeight - 56f)
                        backgroundColor(Color(0x99000000))
                        flexDirectionColumn()
                    }
                    event { click { ctx.tocVisible = false } }
                    View {
                        attr {
                            width(pagerData.pageViewWidth - 40f)
                            margin(20f, 20f, 20f, 20f)
                            backgroundColor(SftpColorTokens.cardBg)
                            borderRadius(10f)
                            padding(12f, 10f, 12f, 10f)
                            flexDirectionColumn()
                        }
                        event { click { } }
                        Text {
                            attr {
                                text("目录")
                                fontSize(15f); fontWeightBold(); color(SftpColorTokens.textPrimary)
                                marginBottom(6f)
                            }
                        }
                        Scroller {
                            attr { flex(1f); flexDirectionColumn() }
                            ctx.mdOutline.forEachIndexed { oi, entry ->
                                Text {
                                    attr {
                                        text(entry.second)
                                        fontSize((13f - entry.first * 0.6f) * ctx.fontScale)
                                        color(SftpColorTokens.textPrimary)
                                        margin(6f, 6f, 6f, 6f)
                                        marginLeft((entry.first - 1) * 10f + 6f)
                                    }
                                    event { click { ctx.jumpToOutline(oi) } }
                                }
                            }
                        }
                        Text {
                            attr {
                                text("关闭")
                                fontSize(13f); color(SftpColorTokens.textSecondary)
                                margin(6f, 6f, 6f, 6f)
                            }
                            event { click { ctx.tocVisible = false } }
                        }
                    }
                }
            }

            // 内容区
            View {
                attr { flex(1f) }
                // 必须用 vif/velse（body 结构层的 when 只在首帧求值 → 加载完成后不会切到内容）
                vif({ ctx.loading }) {
                    SftpLoadingView()
                }
                velseif({ ctx.errorMsg != null }) {
                    SftpErrorView(ctx.errorMsg ?: "") { ctx.detectAndDispatch() }
                }
                velse {
                    // 根据 viewer 渲染对应预览
                    when (ctx.viewer) {
                            ViewerKind.TEXT -> SftpReaderScaffold(
                                metaProvider = { ctx.readerMeta() },
                                onZoomIn = { ctx.zoom(0.15f) },
                                onZoomOut = { ctx.zoom(-0.15f) },
                                wrapProvider = { ctx.wrapLines },
                                onToggleWrap = { ctx.wrapLines = !ctx.wrapLines },
                                onScrollerReady = { ctx.readerScrollTo = it },
                            ) {
                                SftpTextViewer({ ctx.textLines }, { ctx.fontScale }, { ctx.wrapLines }, { ctx.textTruncated })
                            }
                            ViewerKind.MARKDOWN -> SftpReaderScaffold(
                                metaProvider = { ctx.readerMeta() },
                                onZoomIn = { ctx.zoom(0.15f) },
                                onZoomOut = { ctx.zoom(-0.15f) },
                                wrapProvider = { ctx.wrapLines },
                                onToggleWrap = { ctx.wrapLines = !ctx.wrapLines },
                                mdSourceProvider = { ctx.mdSourceView },
                                onToggleSource = { ctx.mdSourceView = !ctx.mdSourceView },
                                tocCountProvider = { ctx.mdOutline.size },
                                onToggleToc = { ctx.tocVisible = !ctx.tocVisible },
                                onScrollerReady = { ctx.readerScrollTo = it },
                            ) {
                                // 源码/预览分支必须用 vif/velse（结构层 if 不会被依赖收集 → 切换不生效）
                                vif({ ctx.mdSourceView }) {
                                    SftpTextViewer({ ctx.textLines }, { ctx.fontScale }, { ctx.wrapLines }, { ctx.textTruncated })
                                }
                                velse {
                                    SftpMarkdownViewer({ ctx.mdBlocks }, { ctx.fontScale }, { ctx.wrapLines }) { /* 链接暂不外跳 */ }
                                }
                            }
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

    /** 状态栏信息：编码 · 大小 · 行数/字数 */
    fun readerMeta(): String {
        val chars = textContent.length
        val lines = textLines.size
        val kb = if (size >= 1024) "${size / 1024}KB" else "${size}B"
        return "$encoding · $kb · $lines 行 · $chars 字"
    }

    companion object {
        const val PAGE_NAME = "SftpViewerDispatcherPage"
        /** Markdown 渲染块上限（超大文档只渲染前 N 块，避免卡死） */
        private const val MAX_MD_BLOCKS = 700
    }
}
