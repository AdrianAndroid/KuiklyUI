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
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.FileModule
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.sftp.OverwriteMode
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.TextArea
import com.tencent.kuikly.demo.pages.base.BridgeModule
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
    /** 已渲染的 Markdown 块数上限（增量渲染；滚动/点按后按批追加） */
    private var mdRenderLimit: Int by observable(INITIAL_MD_BLOCKS)
    /** 已渲染窗口（vforIndex 的数据源：追加时只渲染新增块，避免整篇重建） */
    private var mdVisibleBlocks: ObservableList<MdBlock> by observableList()
    private var textLines: List<String> by observable(emptyList())
    private var mdSourceView: Boolean by observable(false)
    private var wrapLines: Boolean by observable(true)
    private var fontScale: Float by observable(1f)
    private var tocVisible: Boolean by observable(false)
    private var readerScrollTo: ((Float) -> Unit)? = null
    // ---- 编辑 / 保存（对齐 Vditor 的即时渲染 + 显式保存）----
    private var editing: Boolean by observable(false)
    private var editTargetBlock: Int by observable(-1)
    private var editBuffer: String by observable("")
    private var dirty: Boolean by observable(false)
    private var saving: Boolean = false
    private var saveMsg: String by observable("")
    /** 编辑区的「实时预览」块（输入后防抖刷新 = Vditor 即时渲染） */
    private var editPreviewBlocks: List<MdBlock> by observable(emptyList())
    /** 编辑浮层实时预览的 vforIndex 数据源 */
    private var editPreviewVisibleBlocks: ObservableList<MdBlock> by observableList()
    private var editDebounceRef: String? = null

    /** 文档缓存键（连接 + 路径 + 大小）：同一文件重复打开直接复用缓存 */
    private val cacheKey: String
        get() = SftpDocCache.key(connectionId, remotePath, size)

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

    /**
     * 装载全文 → 解析（Markdown 出块与目录；纯文本按行拆分）。
     *
     * 命中 [SftpDocCache] 时**直接使用缓存**（不再走 SFTP 读取/解码/解析）：
     * 这正是「先把文档缓存下来，再做换行/字号/滚动/编辑等操作」的落点。
     */
    private fun loadText() {
        // 缓存优先：重开同一文件秒开（先查缓存，避免闪一下加载态）
        val cached = SftpDocCache.get(cacheKey)
        if (cached != null) {
            loading = false
            applyLoaded(cached.text, cached.encoding, cached.truncated, cached.blocks, cached.outline)
            return
        }
        loading = true
        SftpTextLoader.load(sftpModule(), sessionId, remotePath, size) { content, loadErr ->
            loading = false
            if (content == null) {
                errorMsg = loadErr ?: "读取失败"
                return@load
            }
            val blocks = if (viewer == ViewerKind.MARKDOWN) parseCapped(content.text) else emptyList()
            val outline = if (viewer == ViewerKind.MARKDOWN) safeOutline(blocks) else emptyList()
            // 装载完成即写入缓存，后续操作都基于这份缓存
            SftpDocCache.put(
                cacheKey,
                CachedDoc(
                    text = content.text,
                    encoding = content.encoding,
                    truncated = content.truncated,
                    byteCount = content.byteCount,
                    blocks = blocks,
                    outline = outline
                )
            )
            applyLoaded(content.text, content.encoding, content.truncated, blocks, outline)
        }
    }

    /** 把（缓存或远端的）内容落到页面状态；Markdown 直接用已解析的块，避免重复解析 */
    private fun applyLoaded(
        text: String,
        enc: String,
        truncated: Boolean,
        blocks: List<MdBlock>,
        outline: List<Pair<Int, String>>,
    ) {
        encoding = enc
        textContent = text
        textTruncated = truncated
        if (viewer == ViewerKind.MARKDOWN) {
            mdBlocks = blocks
            mdOutline = outline
            textLines = text.split('\n')
            mdRenderLimit = minOf(maxOf(mdRenderLimit, INITIAL_MD_BLOCKS), blocks.size)
            refreshVisibleBlocks()
            if (mdBlocks.isEmpty()) mdSourceView = true   // 解析失败 → 显示源码，避免空白
        } else {
            textLines = text.split('\n')
        }
    }

    private fun parseCapped(body: String): List<MdBlock> {
        val blocks = try { MarkdownParser.parse(body) } catch (e: Throwable) { emptyList() }
        return if (blocks.size > MAX_MD_BLOCKS) blocks.subList(0, MAX_MD_BLOCKS) else blocks
    }

    private fun safeOutline(blocks: List<MdBlock>): List<Pair<Int, String>> =
        try { MarkdownParser.outline(blocks) } catch (e: Throwable) { emptyList() }

    /** 编辑模式开关（Vditor 的「编辑」入口） */
    private fun toggleEditing() {
        editing = !editing
        editTargetBlock = -1
        editBuffer = ""
        saveMsg = ""
    }

    /** 点某个块 → 用它自己的 Markdown 源码进入编辑 */
    private fun onBlockTap(index: Int) {
        val b = mdBlocks.getOrNull(index) ?: return
        editTargetBlock = index
        editBuffer = b.raw
        refreshEditPreview()
        saveMsg = ""
    }

    /** 完成编辑 → 替换该块源码 → 立即重新解析渲染（即时渲染） */
    private fun applyBlockEdit() {
        val b = mdBlocks.getOrNull(editTargetBlock)
        if (b == null) {
            editTargetBlock = -1
            return
        }
        val lines = textContent.split('\n').toMutableList()
        val start = b.startLine.coerceIn(0, lines.size)
        val end = b.endLine.coerceIn(start, lines.size)
        repeat(end - start) { if (start < lines.size) lines.removeAt(start) }
        lines.addAll(start, editBuffer.split('\n'))
        textContent = lines.joinToString("\n")
        dirty = true
        editTargetBlock = -1
        editBuffer = ""
        editPreviewBlocks = emptyList()
        reparse()
    }

    /** 编辑区内容变化 → 防抖 450ms 后刷新实时预览（即时渲染） */
    private fun onEditBufferChanged(text: String) {
        editBuffer = text
        editDebounceRef?.let { clearTimeout(it) }
        editDebounceRef = setTimeout(450) { refreshEditPreview() }
    }

    private fun refreshEditPreview() {
        editPreviewBlocks = try {
            MarkdownParser.parse(editBuffer)
        } catch (e: Throwable) {
            emptyList()
        }
        editPreviewVisibleBlocks.clear()
        editPreviewVisibleBlocks.addAll(editPreviewBlocks)
    }

    /** 拆出块级前缀（引用 / 列表 / 任务 / 标题），返回 (前缀, 正文) */
    private fun splitBlockPrefix(line: String): Pair<String, String> {
        if (line.startsWith("- [ ] ")) return "- [ ] " to line.substring(6)
        if (line.startsWith("- [x] ") || line.startsWith("- [X] ")) return line.substring(0, 6) to line.substring(6)
        if (line.startsWith("> ")) return "> " to line.substring(2)
        if (line == ">") return "> " to ""
        if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
            return line.substring(0, 2) to line.substring(2)
        }
        var i = 0
        while (i < line.length && line[i].isDigit()) i++
        if (i in 1..9 && i + 1 < line.length && (line[i] == '.' || line[i] == ')') && line[i + 1] == ' ') {
            return line.substring(0, i + 2) to line.substring(i + 2)
        }
        for (h in 6 downTo 1) {
            val p = "#".repeat(h) + " "
            if (line.startsWith(p)) return p to line.substring(p.length)
        }
        return "" to line
    }

    /**
     * 行内包裹：**逐行保留块级前缀**，只把正文包进 open/close。
     *
     * 修复：原先直接 `"**$t**"` 会把引用块的 `> ` 一起包进去（`**> 文本**`），
     * 破坏块结构 → 引用/列表里加粗后正文不再按 Markdown 渲染。
     */
    private fun wrapInline(text: String, open: String, close: String): String =
        text.split('\n').joinToString("\n") { line ->
            val (prefix, body) = splitBlockPrefix(line)
            when {
                body.isEmpty() -> line
                // 幂等：正文已含该标记就不再包裹。
                // 否则对「> **文档目的**：…」再点 B 会变成 `****文档目的**…**` →
                // 解析出空 Bold + 纯文本，表现就是「点了加粗却没变粗」。
                body.contains(open) -> line
                else -> prefix + open + body + close
            }
        }

    /**
     * 格式工具条（对齐 Vditor 的工具栏按钮：标题/加粗/斜体/删除线/行内代码/代码块/引用/列表/任务/链接/表格/分隔线）。
     * 作用于当前编辑块的内容。
     */
    private fun applyFormat(kind: String) {
        val t = editBuffer
        val lines = t.split('\n')
        fun stripHeading(s: String) = s.removePrefix("### ").removePrefix("## ").removePrefix("# ")
        val out = when (kind) {
            "h1" -> lines.mapIndexed { i, l -> if (i == 0) "# " + stripHeading(l) else l }.joinToString("\n")
            "h2" -> lines.mapIndexed { i, l -> if (i == 0) "## " + stripHeading(l) else l }.joinToString("\n")
            "h3" -> lines.mapIndexed { i, l -> if (i == 0) "### " + stripHeading(l) else l }.joinToString("\n")
            // 行内格式：保留块前缀（> / - / 1. / # ），避免破坏引用与列表结构
            "bold" -> wrapInline(t, "**", "**")
            "italic" -> wrapInline(t, "*", "*")
            "strike" -> wrapInline(t, "~~", "~~")
            "code" -> wrapInline(t, "`", "`")
            "codeblock" -> "```\n$t\n```"
            "quote" -> lines.joinToString("\n") { if (it.startsWith("> ")) it else "> $it" }
            "ul" -> lines.joinToString("\n") { if (it.startsWith("- ")) it else "- $it" }
            "ol" -> lines.mapIndexed { i, l -> "${i + 1}. " + l.removePrefix("- ") }.joinToString("\n")
            "task" -> lines.joinToString("\n") { if (it.startsWith("- [ ] ")) it else "- [ ] $it" }
            "link" -> wrapInline(t, "[", "](https://)")
            "table" -> "| 列1 | 列2 |\n| --- | --- |\n| $t |  |"
            "hr" -> "$t\n\n---"
            else -> t
        }
        editBuffer = out
        refreshEditPreview()
    }

    private fun cancelBlockEdit() {
        editTargetBlock = -1
        editBuffer = ""
        editPreviewBlocks = emptyList()
    }

    /** 重新解析并刷新（编辑后即时渲染），同时把最新内容回写缓存 */
    private fun reparse() {
        val body = textContent
        val capped = parseCapped(body)
        val outline = safeOutline(capped)
        mdBlocks = capped
        mdOutline = outline
        textLines = body.split('\n')
        // 已渲染窗口至少保持初始批量，且不超过总块数（编辑后不塌回顶部）
        mdRenderLimit = minOf(maxOf(mdRenderLimit, INITIAL_MD_BLOCKS), capped.size)
        refreshVisibleBlocks()
        // 编辑/保存后缓存与远端保持一致
        SftpDocCache.put(
            cacheKey,
            CachedDoc(
                text = body,
                encoding = encoding,
                truncated = textTruncated,
                byteCount = size,
                blocks = capped,
                outline = outline
            )
        )
    }

    /**
     * 按当前窗口上限刷新 mdVisibleBlocks。
     *
     * - 前缀一致（只是窗口变大）→ 增量 `addAll`，vforIndex 只渲染新增块；
     * - 内容变化（编辑/重新解析）→ 换一个**新实例**，走 vforIndex 明确支持的「整体替换」路径。
     *   不用 `clear()+addAll()`：同一帧内两个操作与 vforIndex 的惰性同步存在竞态，曾导致正文偶发空白。
     */
    private fun refreshVisibleBlocks() {
        val limit = mdRenderLimit.coerceAtLeast(1)
        val want = if (mdBlocks.size > limit) mdBlocks.subList(0, limit) else mdBlocks
        val canAppend = mdVisibleBlocks.size <= want.size &&
            (0 until mdVisibleBlocks.size).all { mdVisibleBlocks[it] == want[it] }
        if (canAppend) {
            if (want.size > mdVisibleBlocks.size) {
                mdVisibleBlocks.addAll(want.subList(mdVisibleBlocks.size, want.size))
            }
            return
        }
        mdVisibleBlocks = ObservableList(want.toMutableList())
    }

    /** 点击底部提示：追加一批块（web/Electron 的 Scroller 不上报滚动偏移，故显式触发） */
    private fun loadMoreBlocks() {
        if (mdRenderLimit < mdBlocks.size) {
            mdRenderLimit = minOf(mdRenderLimit + INITIAL_MD_BLOCKS, mdBlocks.size)
            refreshVisibleBlocks()
        }
    }

    /** 滚动到接近「已渲染底部」时追加一批块（原生端上报偏移时自动生效） */
    private fun onReaderScroll(offsetY: Float) {
        if (mdBlocks.isEmpty() || mdRenderLimit >= mdBlocks.size) return
        val renderedBottom = mdRenderLimit * AVG_MD_BLOCK_H
        val viewport = pagerData.pageViewHeight
        if (offsetY + viewport >= renderedBottom - MD_LOAD_MARGIN) {
            mdRenderLimit = minOf(mdRenderLimit + INITIAL_MD_BLOCKS, mdBlocks.size)
            refreshVisibleBlocks()
        }
    }

    /**
     * 保存：web/桌面把内容写入宿主本地临时文件（localFs）后 upload；
     * 其它端写入应用沙盒（FileModule）后 upload —— 不新增各端原生方法。
     */
    private fun save() {
        if (saving) return
        saving = true
        saveMsg = "保存中…"
        val b64 = SftpTextLoader.base64(SftpTextLoader.encodeUtf8(textContent))
        val web = runCatching {
            acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).supportsPlayerWindow()
        }.getOrDefault(false)
        if (web) {
            acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).saveTempFile(b64) { path ->
                if (path.isNullOrEmpty()) {
                    saving = false
                    saveMsg = "保存失败：无法写入宿主临时文件"
                } else {
                    uploadSaved(path)
                }
            }
        } else {
            val fm = acquireModule<FileModule>(FileModule.MODULE_NAME)
            fm.writeFile(TMP_FILE_NAME, textContent) {
                fm.getFilesDir { res ->
                    val d = (res?.optString("dir") ?: res?.optString("path")).orEmpty()
                    if (d.isEmpty()) {
                        saving = false
                        saveMsg = "保存失败：无法获取沙盒目录"
                    } else {
                        uploadSaved("$d/$TMP_FILE_NAME")
                    }
                }
            }
        }
    }

    private fun uploadSaved(localPath: String) {
        val sid = sessionId.ifEmpty { connectionId }
        sftpModule().upload(sid, localPath, remotePath, overwrite = OverwriteMode.OVERWRITE) { _, ok, err ->
            saving = false
            if (err != null || !ok) {
                saveMsg = "保存失败：" + (err?.msg ?: "未知错误")
            } else {
                dirty = false
                saveMsg = "已保存 $name"
            }
        }
    }

    private fun zoom(delta: Float) {
        fontScale = (fontScale + delta).coerceIn(0.7f, 2.2f)
    }

    private fun jumpToOutline(index: Int) {
        tocVisible = false
        // 找到第 index 个（level<=3）标题所在的块下标
        var seen = 0
        var blockIndex = -1
        mdBlocks.forEachIndexed { i, b ->
            if (blockIndex < 0 && b is MdBlock.Heading && b.level <= 3) {
                if (seen == index) blockIndex = i else seen++
            }
        }
        if (blockIndex < 0) blockIndex = 0
        // 目标块可能还没渲染（增量渲染）：先把窗口撑到覆盖它，再滚动，避免跳到占位区
        if (blockIndex + 1 > mdRenderLimit) {
            mdRenderLimit = minOf(blockIndex + INITIAL_MD_BLOCKS, mdBlocks.size)
            refreshVisibleBlocks()
        }
        // 块高不固定，按平均块高近似定位
        readerScrollTo?.invoke(blockIndex * AVG_MD_BLOCK_H)
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
                                dirtyProvider = { ctx.dirty },
                                onSave = { ctx.save() },
                                saveMsgProvider = { ctx.saveMsg },
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
                                onScroll = { ctx.onReaderScroll(it) },
                                editingProvider = { ctx.editing },
                                onToggleEditing = { ctx.toggleEditing() },
                                dirtyProvider = { ctx.dirty },
                                onSave = { ctx.save() },
                                saveMsgProvider = { ctx.saveMsg },
                            ) {
                                // 源码/预览分支必须用 vif/velse（结构层 if 不会被依赖收集 → 切换不生效）
                                vif({ ctx.mdSourceView }) {
                                    SftpTextViewer({ ctx.textLines }, { ctx.fontScale }, { ctx.wrapLines }, { ctx.textTruncated })
                                }
                                velse {
                                    SftpMarkdownViewer(
                                        { ctx.mdBlocks }, { ctx.mdVisibleBlocks }, { ctx.fontScale }, { ctx.wrapLines },
                                        onLink = { /* 链接暂不外跳 */ },
                                        editingProvider = { ctx.editing },
                                        editTargetProvider = { ctx.editTargetBlock },
                                        onBlockTap = { ctx.onBlockTap(it) },
                                        onLoadMore = { ctx.loadMoreBlocks() },
                                    )
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
            // 块编辑浮层：格式工具条（对齐 Vditor 工具栏）+ 编辑区 + 实时预览（即时渲染）
            vif({ ctx.editTargetBlock >= 0 }) {
                View {
                    attr {
                        positionAbsolute(); left(0f); top(0f)
                        size(pagerData.pageViewWidth, pagerData.pageViewHeight)
                        backgroundColor(Color(0x99000000))
                        flexDirectionColumn()
                        padding(14f, 14f, 14f, 14f)
                    }
                    View {
                        attr {
                            width(pagerData.pageViewWidth - 28f)
                            flex(1f)
                            backgroundColor(SftpColorTokens.cardBg)
                            borderRadius(10f)
                            padding(10f, 8f, 10f, 8f)
                            flexDirectionColumn()
                        }
                        // 标题行
                        View {
                            attr { flexDirectionRow(); alignItemsCenter(); marginBottom(4f) }
                            Text {
                                attr {
                                    text("编辑（第 ${ctx.editTargetBlock + 1} 块）· 输入即预览")
                                    fontSize(12f); fontWeightBold(); color(SftpColorTokens.textPrimary)
                                    flex(1f)
                                }
                            }
                            Text {
                                attr { text("取消"); fontSize(12f); color(SftpColorTokens.textSecondary); margin(6f, 6f, 6f, 6f) }
                                event { click { ctx.cancelBlockEdit() } }
                            }
                            Text {
                                // 注意：不要叫「完成」——工具条的编辑开关也叫完成，会撞名导致自动化点到开关
                                attr { text("应用"); fontSize(12f); color(SftpColorTokens.primary); margin(6f, 6f, 6f, 6f) }
                                event { click { ctx.applyBlockEdit() } }
                            }
                        }
                        // 格式工具条（Vditor 样式的一排按钮）
                        View {
                            attr { flexDirectionRow(); alignItemsCenter(); flexWrapWrap() }
                            MdFmtChip("H1") { ctx.applyFormat("h1") }
                            MdFmtChip("H2") { ctx.applyFormat("h2") }
                            MdFmtChip("H3") { ctx.applyFormat("h3") }
                            MdFmtChip("B") { ctx.applyFormat("bold") }
                            MdFmtChip("I") { ctx.applyFormat("italic") }
                            MdFmtChip("S") { ctx.applyFormat("strike") }
                            MdFmtChip("`") { ctx.applyFormat("code") }
                            MdFmtChip("```") { ctx.applyFormat("codeblock") }
                            MdFmtChip(">") { ctx.applyFormat("quote") }
                            MdFmtChip("•") { ctx.applyFormat("ul") }
                            MdFmtChip("1.") { ctx.applyFormat("ol") }
                            MdFmtChip("☐") { ctx.applyFormat("task") }
                            MdFmtChip("🔗") { ctx.applyFormat("link") }
                            MdFmtChip("▦") { ctx.applyFormat("table") }
                            MdFmtChip("―") { ctx.applyFormat("hr") }
                        }
                        // 编辑区
                        TextArea {
                            attr {
                                text(ctx.editBuffer)
                                height(120f)
                                fontSize(12.5f)
                                color(SftpColorTokens.textPrimary)
                                backgroundColor(SftpColorTokens.bg)
                                borderRadius(6f)
                                placeholder("Markdown 源码…")
                            }
                            event { textDidChange { e -> ctx.onEditBufferChanged(e.text) } }
                        }
                        // 实时预览
                        Text {
                            attr {
                                text("实时预览")
                                fontSize(11f); color(SftpColorTokens.textSecondary)
                                margin(6f, 0f, 6f, 2f)
                            }
                        }
                        Scroller {
                            attr { flex(1f); flexDirectionColumn(); backgroundColor(SftpColorTokens.bg); borderRadius(6f); padding(6f, 6f, 6f, 6f) }
                            SftpMarkdownViewer(
                                { ctx.editPreviewBlocks }, { ctx.editPreviewVisibleBlocks },
                                { ctx.fontScale }, { ctx.wrapLines },
                                onLink = { },
                            )
                        }
                    }
                }
            }

            // 目录：二级弹窗（底部抽屉，独立滚动；条目点击后关闭并近似跳转）
            vif({ ctx.tocVisible && ctx.mdOutline.isNotEmpty() }) {
                View {
                    attr {
                        positionAbsolute(); left(0f); top(0f)
                        size(pagerData.pageViewWidth, pagerData.pageViewHeight)
                        backgroundColor(Color(0x88000000))
                        flexDirectionColumn()
                    }
                    // 点击遮罩关闭
                    event { click { ctx.tocVisible = false } }
                    View { attr { flex(1f) } }
                    // 弹窗主体：底部抽屉（固定高度、圆角、独立滚动）
                    View {
                        attr {
                            width(pagerData.pageViewWidth)
                            height(pagerData.pageViewHeight * 0.62f)
                            backgroundColor(SftpColorTokens.cardBg)
                            borderRadius(14f)
                            flexDirectionColumn()
                            padding(14f, 12f, 14f, 12f)
                        }
                        // 阻止穿透到遮罩
                        event { click { } }
                        // 头部
                        View {
                            attr { flexDirectionRow(); alignItemsCenter(); marginBottom(8f) }
                            Text {
                                attr {
                                    text("目录 · ${ctx.mdOutline.size} 项")
                                    fontSize(15f); fontWeightBold(); color(SftpColorTokens.textPrimary)
                                    flex(1f)
                                }
                            }
                            Text {
                                attr { text("关闭"); fontSize(13f); color(SftpColorTokens.textSecondary); margin(6f, 6f, 6f, 6f) }
                                event { click { ctx.tocVisible = false } }
                            }
                        }
                        // 条目列表（独立滚动）
                        Scroller {
                            attr {
                                flex(1f)
                                width(pagerData.pageViewWidth - 28f)
                                flexDirectionColumn()
                                showScrollerIndicator(true)
                            }
                            ctx.mdOutline.forEachIndexed { oi, entry ->
                                View {
                                    attr {
                                        width(pagerData.pageViewWidth - 36f)
                                        padding(10f, 8f, 10f, 8f)
                                        flexDirectionRow()
                                        alignItemsCenter()
                                    }
                                    event { click { ctx.jumpToOutline(oi) } }
                                    if (entry.first > 1) {
                                        View { attr { width((entry.first - 1) * 14f); height(1f) } }
                                    }
                                    Text {
                                        attr {
                                            text(entry.second)
                                            fontSize((14f - entry.first * 0.5f) * ctx.fontScale)
                                            color(if (entry.first == 1) SftpColorTokens.textPrimary else SftpColorTokens.textSecondary)
                                            flex(1f)
                                            lines(2)
                                        }
                                    }
                                    Text {
                                        attr { text("›"); fontSize(14f); color(SftpColorTokens.textSecondary); marginLeft(6f) }
                                    }
                                }
                            }
                        }
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
        /** 首屏渲染块数 / 每次滚动追加的块数（增量渲染，控制存活视图数） */
        private const val INITIAL_MD_BLOCKS = 40
        /** 平均块高估算（目录跳转/滚动追加的近似定位，块高不固定） */
        private const val AVG_MD_BLOCK_H = 58f
        /** 距已渲染底部多少像素时追加下一批 */
        private const val MD_LOAD_MARGIN = 240f
        /** 保存时写入宿主/沙盒的临时文件名 */
        private const val TMP_FILE_NAME = "kuikly_viewer_edit_tmp.md"
    }
}

/** 浮层里的格式按钮（对齐 Vditor 工具条样式：小圆角胶囊） */
private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.MdFmtChip(label: String, onClick: () -> Unit) {
    View {
        attr {
            backgroundColor(SftpColorTokens.bg)
            borderRadius(5f)
            padding(6f, 4f, 6f, 4f)
            margin(2f, 2f, 2f, 2f)
            allCenter()
        }
        event { click { onClick() } }
        Text { attr { text(label); fontSize(11.5f); color(SftpColorTokens.textPrimary); lines(1) } }
    }
}
