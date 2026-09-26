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

import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vforIndex
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.RichText
import com.tencent.kuikly.core.views.Span
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens
import com.tencent.kuikly.demo.pages.sftp.viewer.md.CodeHighlighter
import com.tencent.kuikly.demo.pages.sftp.viewer.md.CodeTokenType
import com.tencent.kuikly.demo.pages.sftp.viewer.md.MarkdownParser
import com.tencent.kuikly.demo.pages.sftp.viewer.md.MdBlock
import com.tencent.kuikly.demo.pages.sftp.viewer.md.MdItem
import com.tencent.kuikly.demo.pages.sftp.viewer.md.MdRun

/**
 * Markdown 渲染（参考 MarkText 的渲染范围子集）。
 *
 * 行内样式用 `RichText + Span`（单一文本流，能正确跨行折行）。
 * 响应式约定：**所有可变状态都以 provider 传入，并在 `attr {}` / `vif` 条件内读取**
 * —— 否则依赖不会被收集，加载完成或切换字号后不会重渲染（本仓库的经典坑）。
 */
internal fun ViewContainer<*, *>.SftpMarkdownViewer(
    blocksProvider: () -> List<MdBlock>,
    /** 已渲染窗口（ObservableList）：增量追加时由 vforIndex 只重建新增的块 */
    visibleBlocksProvider: () -> ObservableList<MdBlock>,
    fontScaleProvider: () -> Float,
    wrapProvider: () -> Boolean,
    onLink: (String) -> Unit,
    editingProvider: () -> Boolean = { false },
    editTargetProvider: () -> Int = { -1 },
    onBlockTap: (Int) -> Unit = { },
    /** 追加一批块（点击底部提示触发；原生端若上报滚动偏移也会自动触发） */
    onLoadMore: (() -> Unit)? = null,
) {
    vif({ blocksProvider().isNotEmpty() }) {
        SftpMarkdownBody(
            visibleBlocksProvider,
            totalCountProvider = { blocksProvider().size },
            renderedCountProvider = { visibleBlocksProvider().size },
            fontScaleProvider, wrapProvider, onLink,
            editingProvider, editTargetProvider, onBlockTap, onLoadMore
        )
    }
    velse {
        View {
            attr { flex(1f); allCenter() }
            Text { attr { text("(空文档)"); fontSize(13f); color(SftpColorTokens.textSecondary) } }
        }
    }
}

private fun ViewContainer<*, *>.SftpMarkdownBody(
    visibleBlocksProvider: () -> ObservableList<MdBlock>,
    totalCountProvider: () -> Int,
    renderedCountProvider: () -> Int,
    fontScaleProvider: () -> Float,
    wrapProvider: () -> Boolean,
    onLink: (String) -> Unit,
    editingProvider: () -> Boolean,
    editTargetProvider: () -> Int,
    onBlockTap: (Int) -> Unit,
    onLoadMore: (() -> Unit)?,
) {
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            backgroundColor(SftpColorTokens.cardBg)
            borderRadius(8f)
            padding(14f, 14f, 14f, 14f)
        }
        // 用 vforIndex 驱动：增量窗口变化时只重建/追加变化的块（依赖能被正确收集）
        vforIndex({ visibleBlocksProvider() }) { block, index, _ ->
            // 编辑模式：每个块可点（进来改这一块的 Markdown 源码，完成即重渲染 = 即时渲染）
            View {
                attr {
                    backgroundColor(
                        if (editingProvider() && editTargetProvider() == index) Color(0x22007AFF)
                        else Color.TRANSPARENT
                    )
                    borderRadius(4f)
                }
                event { click { if (editingProvider()) onBlockTap(index) } }
                when (block) {
                    is MdBlock.Heading -> MdHeading(block, fontScaleProvider)
                    is MdBlock.Paragraph -> MdInlineText(block.runs, 14f, fontScaleProvider, wrapProvider, onLink, 6f)
                    is MdBlock.Code -> MdCode(block, fontScaleProvider)
                    is MdBlock.Quote -> MdQuote(block.runs, fontScaleProvider, wrapProvider, onLink)
                    is MdBlock.ListBlock -> MdList(block.items, fontScaleProvider, wrapProvider, onLink)
                    is MdBlock.Diagram -> MdDiagram(block, fontScaleProvider)
                    is MdBlock.Table -> MdTable(block, fontScaleProvider)
                    is MdBlock.Hr -> View {
                        attr { height(1f); backgroundColor(SftpColorTokens.divider); margin(10f, 0f, 10f, 0f) }
                    }
                }
            }
        }
        // 增量渲染占位：还有未渲染的块时给出提示（点按或原生端滚动自动追加）
        vif({ totalCountProvider() > renderedCountProvider() }) {
            View {
                attr { height(40f); allCenter() }
                if (onLoadMore != null) { event { click { onLoadMore.invoke() } } }
                Text {
                    attr {
                        text(
                            if (onLoadMore != null) {
                                "点此加载更多 · 已渲染 ${renderedCountProvider()}/${totalCountProvider()} 块"
                            } else {
                                "已渲染 ${renderedCountProvider()}/${totalCountProvider()} 块"
                            }
                        )
                        fontSize(11f)
                        color(SftpColorTokens.textSecondary)
                        accessibility("md_more")
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.MdHeading(block: MdBlock.Heading, fontScaleProvider: () -> Float) {
    val base = when (block.level) {
        1 -> 26f; 2 -> 22f; 3 -> 19f; 4 -> 17f; 5 -> 15f; else -> 14f
    }
    View {
        attr {
            margin(if (block.level == 1) 6f else 14f, 0f, 6f, 0f)
            flexDirectionRow(); alignItemsCenter()
        }
        if (block.level <= 2) {
            View {
                attr {
                    width(3f)
                    // 在 attr 内读 provider → 依赖被收集，缩放字号会跟着变
                    height(base * fontScaleProvider() + 4f)
                    backgroundColor(SftpColorTokens.primary)
                    borderRadius(2f)
                    marginRight(8f)
                }
            }
        }
        Text {
            attr {
                text(block.text)
                fontSize(base * fontScaleProvider())
                fontWeightBold()
                color(SftpColorTokens.textPrimary)
                flex(1f)
            }
        }
    }
}

private fun ViewContainer<*, *>.MdQuote(
    runs: List<MdRun>,
    fontScaleProvider: () -> Float,
    wrapProvider: () -> Boolean,
    onLink: (String) -> Unit,
) {
    View {
        attr {
            flexDirectionRow()
            margin(8f, 0f, 8f, 0f)
            backgroundColor(Color(0x11000000))
            borderRadius(6f)
        }
        View { attr { width(3f); backgroundColor(SftpColorTokens.primary); borderRadius(2f); marginRight(8f) } }
        View {
            attr { flex(1f); padding(8f, 6f, 8f, 6f) }
            MdInlineText(runs, 13f, fontScaleProvider, wrapProvider, onLink, 0f)
        }
    }
}

private fun ViewContainer<*, *>.MdCode(
    block: MdBlock.Code,
    fontScaleProvider: () -> Float,
) {
    View {
        attr {
            margin(8f, 0f, 8f, 0f)
            backgroundColor(Color(0xFF1E1E1E))
            borderRadius(6f)
            padding(10f, 10f, 10f, 10f)
            flexDirectionColumn()
        }
        if (block.lang.isNotEmpty()) {
            Text {
                attr {
                    text(block.lang)
                    fontSize(10f * fontScaleProvider())
                    color(Color(0xFF9CA3AF))
                    marginBottom(6f)
                }
            }
        }
        // 语法高亮（参考 MarkText / VS Code Dark+ 配色）：按 token 上色。
        // 每个源码行一个 RichText（保留换行与缩进），最多高亮 MAX_HIGHLIGHT_LINES 行。
        // 代码正文**始终按宽度折行**（不做「不换行」）：不换行时每条长行会溢出后被父容器
        // `overflow:hidden` 裁掉，而 Kuikly 文本给不出可靠本征宽度、横向 Scroller 滚不动
        // → 用户看到「长的代码显示不全」。折行可保证六端都完整可见。
        val hlLines = CodeHighlighter.highlight(block.lang, block.code)
        hlLines.forEach { toks ->
            RichText {
                attr {
                    fontSize(12f * fontScaleProvider())
                    color(codeTokenColor(CodeTokenType.PLAIN))
                    lineHeight(18f * fontScaleProvider())
                    fontFamily("monospace")
                }
                toks.forEach { tk ->
                    Span {
                        text(tk.text.ifEmpty { " " })   // 空行给一个空格，保持行高
                        color(codeTokenColor(tk.type))
                    }
                }
            }
        }
        // 超出高亮上限的部分：单色纯文本（避免为超大代码块建过多视图）
        if (CodeHighlighter.lineCount(block.code) > hlLines.size) {
            Text {
                attr {
                    text(CodeHighlighter.tailPlain(block.code, hlLines.size))
                    fontSize(12f * fontScaleProvider())
                    color(codeTokenColor(CodeTokenType.PLAIN))
                    lineHeight(18f * fontScaleProvider())
                    fontFamily("monospace")
                }
            }
        }
    }
}

/** 代码 token → 颜色（Dark+ 主题，配合 #1E1E1E 代码底色）。 */
private fun codeTokenColor(t: CodeTokenType): Color = when (t) {
    CodeTokenType.KEYWORD -> Color(0xFF569CD6.toInt())
    CodeTokenType.STRING -> Color(0xFFCE9178.toInt())
    CodeTokenType.COMMENT -> Color(0xFF6A9955.toInt())
    CodeTokenType.NUMBER -> Color(0xFFB5CEA8.toInt())
    CodeTokenType.FUNCTION -> Color(0xFFDCDCAA.toInt())
    CodeTokenType.TYPE -> Color(0xFF4EC9B0.toInt())
    CodeTokenType.ANNOTATION -> Color(0xFFDCDCAA.toInt())
    CodeTokenType.ATTR -> Color(0xFF9CDCFE.toInt())
    CodeTokenType.PUNCT -> Color(0xFFD4D4D4.toInt())
    CodeTokenType.PLAIN -> Color(0xFFD4D4D4.toInt())
}

private fun ViewContainer<*, *>.MdList(
    items: List<MdItem>,
    fontScaleProvider: () -> Float,
    wrapProvider: () -> Boolean,
    onLink: (String) -> Unit,
) {
    View {
        attr { flexDirectionColumn(); margin(4f, 0f, 4f, 0f) }
        items.forEach { item ->
            View {
                attr { flexDirectionRow(); margin(2f, 0f, 2f, 0f) }
                if (item.indent > 0) {
                    View { attr { width(14f * item.indent); height(1f) } }
                }
                Text {
                    attr {
                        text(
                            when {
                                item.task == true -> if (item.checked) "☑ " else "☐ "
                                item.ordered -> "${item.index}. "
                                else -> "• "
                            }
                        )
                        fontSize(13f * fontScaleProvider())
                        color(if (item.task == true && item.checked) SftpColorTokens.primary else SftpColorTokens.textSecondary)
                        marginRight(4f)
                    }
                }
                View {
                    attr { flex(1f) }
                    MdInlineText(item.runs, 13.5f, fontScaleProvider, wrapProvider, onLink, 0f)
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.MdTable(block: MdBlock.Table, fontScaleProvider: () -> Float) {
    val columns = block.header.size.coerceAtLeast(1)
    View {
        attr {
            flexDirectionColumn()
            margin(8f, 0f, 8f, 0f)
            borderRadius(6f)
            backgroundColor(SftpColorTokens.bg)
            padding(2f, 2f, 2f, 2f)
        }
        fun row(cells: List<String>, header: Boolean) {
            View {
                attr { flexDirectionRow(); marginBottom(1f) }
                for (c in 0 until columns) {
                    val cell = cells.getOrNull(c) ?: ""
                    Text {
                        attr {
                            text(cell)
                            fontSize((if (header) 12.5f else 12f) * fontScaleProvider())
                            color(if (header) SftpColorTokens.textPrimary else SftpColorTokens.textSecondary)
                            if (header) fontWeightBold()
                            flex(1f)
                            margin(6f, 5f, 6f, 5f)
                            backgroundColor(if (header) SftpColorTokens.cardBg else Color.TRANSPARENT)
                        }
                    }
                }
            }
        }
        row(block.header, true)
        block.rows.forEach { r -> row(r, false) }
    }
}

/** 行内片段 → 单个 RichText（正确的跨行折行；字号在 attr 内读 provider） */
private fun ViewContainer<*, *>.MdInlineText(
    runs: List<MdRun>,
    baseSize: Float,
    fontScaleProvider: () -> Float,
    wrapProvider: () -> Boolean,
    onLink: (String) -> Unit,
    top: Float,
) {
    RichText {
        attr {
            fontSize(baseSize * fontScaleProvider())
            color(SftpColorTokens.textPrimary)
            lineHeight(baseSize * fontScaleProvider() * 1.6f)
            marginTop(top)
            if (!wrapProvider()) lines(1)
        }
        runs.forEach { run ->
            Span {
                when (run) {
                    is MdRun.Plain -> { text(run.text); fontSize(baseSize * fontScaleProvider()) }
                    is MdRun.Bold -> { text(run.text); fontSize(baseSize * fontScaleProvider()); fontWeightBold() }
                    is MdRun.Italic -> { text(run.text); fontSize(baseSize * fontScaleProvider()) }
                    is MdRun.Strike -> {
                        text(run.text); fontSize(baseSize * fontScaleProvider()); textDecorationLineThrough()
                    }
                    is MdRun.Code -> {
                        text(run.text)
                        fontSize(baseSize * fontScaleProvider() * 0.92f)
                        color(Color(0xFFB00020))
                        backgroundColor(Color(0x11000000))
                        fontFamily("monospace")
                    }
                    is MdRun.Link -> {
                        text(run.label)
                        fontSize(baseSize * fontScaleProvider())
                        color(SftpColorTokens.primary)
                        click { onLink(run.url) }
                    }
                }
            }
        }
    }
}
