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
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.RichText
import com.tencent.kuikly.core.views.Span
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens
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
    fontScaleProvider: () -> Float,
    wrapProvider: () -> Boolean,
    onLink: (String) -> Unit,
    editingProvider: () -> Boolean = { false },
    editTargetProvider: () -> Int = { -1 },
    onBlockTap: (Int) -> Unit = { },
) {
    vif({ blocksProvider().isNotEmpty() }) {
        SftpMarkdownBody(
            blocksProvider(), fontScaleProvider, wrapProvider, onLink,
            editingProvider, editTargetProvider, onBlockTap
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
    blocks: List<MdBlock>,
    fontScaleProvider: () -> Float,
    wrapProvider: () -> Boolean,
    onLink: (String) -> Unit,
    editingProvider: () -> Boolean,
    editTargetProvider: () -> Int,
    onBlockTap: (Int) -> Unit,
) {
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            backgroundColor(SftpColorTokens.cardBg)
            borderRadius(8f)
            padding(14f, 14f, 14f, 14f)
        }
        blocks.forEachIndexed { index, block ->
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
                    is MdBlock.Paragraph -> MdInlineText(MarkdownParser.parseInline(block.text), 14f, fontScaleProvider, wrapProvider, onLink, 6f)
                    is MdBlock.Code -> MdCode(block, fontScaleProvider, wrapProvider)
                    is MdBlock.Quote -> MdQuote(block.text, fontScaleProvider, wrapProvider, onLink)
                    is MdBlock.ListBlock -> MdList(block.items, fontScaleProvider, wrapProvider, onLink)
                    is MdBlock.Table -> MdTable(block, fontScaleProvider)
                    is MdBlock.Hr -> View {
                        attr { height(1f); backgroundColor(SftpColorTokens.divider); margin(10f, 0f, 10f, 0f) }
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
    text: String,
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
            MdInlineText(MarkdownParser.parseInline(text), 13f, fontScaleProvider, wrapProvider, onLink, 0f)
        }
    }
}

private fun ViewContainer<*, *>.MdCode(
    block: MdBlock.Code,
    fontScaleProvider: () -> Float,
    wrapProvider: () -> Boolean,
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
        Text {
            attr {
                text(block.code)
                fontSize(12f * fontScaleProvider())
                color(Color(0xFFE6E6E6))
                lineHeight(18f * fontScaleProvider())
                fontFamily("monospace")
                if (!wrapProvider()) lines(1)
            }
        }
    }
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
                    MdInlineText(MarkdownParser.parseInline(item.text), 13.5f, fontScaleProvider, wrapProvider, onLink, 0f)
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
