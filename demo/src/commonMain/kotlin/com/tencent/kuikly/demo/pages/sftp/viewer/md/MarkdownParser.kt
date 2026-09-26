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
package com.tencent.kuikly.demo.pages.sftp.viewer.md

/**
 * Markdown 块级模型（纯数据，便于单测）。
 *
 * 参考实现：MarkText（MIT，Electron 富功能 Markdown 阅读/编辑器）的渲染范围
 * —— 标题 / 段落 / 代码块 / 引用 / 有序无序列表 / 任务列表 / 表格 / 分隔线，
 * 行内支持 粗体 / 斜体 / 删除线 / 行内代码 / 链接。
 */
internal sealed class MdBlock {
    /** 源码原文（编辑块时作为初始内容；替换回文档时也用它对齐行范围） */
    abstract val raw: String
    /** 起始行号（0 起，含） */
    abstract val startLine: Int
    /** 结束行号（0 起，不含） */
    abstract val endLine: Int

    data class Heading(
        val level: Int, val text: String,
        override val raw: String, override val startLine: Int, override val endLine: Int
    ) : MdBlock()

    data class Paragraph(
        val text: String,
        /** 行内片段在解析期一次算好，渲染期不再重复解析（拖动窗口/切换行时的主要卡顿来源之一） */
        val runs: List<MdRun>,
        override val raw: String, override val startLine: Int, override val endLine: Int
    ) : MdBlock()

    data class Code(
        val lang: String, val code: String,
        override val raw: String, override val startLine: Int, override val endLine: Int
    ) : MdBlock()

    /** 图表代码块（```mermaid / ```flow 等）：由各端/共享渲染器绘制 */
    data class Diagram(
        val kind: String, val code: String,
        override val raw: String, override val startLine: Int, override val endLine: Int
    ) : MdBlock()

    data class Quote(
        val text: String,
        val runs: List<MdRun>,
        override val raw: String, override val startLine: Int, override val endLine: Int
    ) : MdBlock()

    data class ListBlock(
        val items: List<MdItem>,
        override val raw: String, override val startLine: Int, override val endLine: Int
    ) : MdBlock()

    data class Table(
        val header: List<String>, val rows: List<List<String>>,
        override val raw: String, override val startLine: Int, override val endLine: Int
    ) : MdBlock()

    data class Hr(
        override val raw: String, override val startLine: Int, override val endLine: Int
    ) : MdBlock()
}

/** 列表项：indent 用于表达层级（0 起） */
internal data class MdItem(
    val text: String,
    /** 行内片段在解析期一次算好（同 Paragraph） */
    val runs: List<MdRun> = emptyList(),
    val ordered: Boolean,
    val index: Int,
    val indent: Int,
    val task: Boolean? = null,
    val checked: Boolean = false
)

/** 行内片段（粗体/斜体等嵌套用 runs 表达） */
internal sealed class MdRun {
    data class Plain(val text: String) : MdRun()
    data class Bold(val text: String) : MdRun()
    data class Italic(val text: String) : MdRun()
    data class Strike(val text: String) : MdRun()
    data class Code(val text: String) : MdRun()
    data class Link(val label: String, val url: String) : MdRun()
}

/**
 * 轻量 Markdown 解析器：只做渲染需要的子集，不做 CommonMark 全兼容。
 * 纯函数、无平台依赖 → commonMain 全端可用，并可用单测锁定行为。
 */
internal object MarkdownParser {

    /** 走 Diagram 渲染的代码块语言（图表 DSL） */
    private val DIAGRAM_LANGS = setOf("mermaid", "flow", "flowchart", "graphviz", "dot")

    fun parse(source: String): List<MdBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<MdBlock>()
        val para = StringBuilder()
        var paraStart = -1
        var i = 0

        fun flushPara(endLine: Int) {
            if (para.isNotEmpty()) {
                val text = para.toString().trim()
                blocks.add(
                    MdBlock.Paragraph(
                        text = text,
                        runs = parseInline(text),
                        raw = lines.subList(paraStart, endLine).joinToString("\n"),
                        startLine = paraStart,
                        endLine = endLine
                    )
                )
                para.setLength(0)
                paraStart = -1
            }
        }
        fun rawOf(from: Int, toExclusive: Int) = lines.subList(from, toExclusive).joinToString("\n")

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // 围栏代码块 ```lang
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                flushPara(i)
                val from = i
                val fence = trimmed.take(3)
                val lang = trimmed.removePrefix(fence).trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                i++ // 跳过结束围栏
                val body = code.toString().trimEnd('\n')
                // 图表代码块（mermaid / flow / graphviz 等）走 Diagram，由渲染器绘制
                if (lang.lowercase() in DIAGRAM_LANGS) {
                    blocks.add(
                        MdBlock.Diagram(
                            kind = lang.lowercase(), code = body,
                            raw = rawOf(from, i), startLine = from, endLine = i
                        )
                    )
                } else {
                    blocks.add(
                        MdBlock.Code(
                            lang, body,
                            raw = rawOf(from, i), startLine = from, endLine = i
                        )
                    )
                }
                continue
            }

            // 标题
            val h = headingLevel(trimmed)
            if (h > 0) {
                flushPara(i)
                blocks.add(
                    MdBlock.Heading(
                        h, trimmed.drop(h).trim().trimEnd('#').trim(),
                        raw = line, startLine = i, endLine = i + 1
                    )
                )
                i++
                continue
            }

            // 分隔线
            if (isHr(trimmed)) {
                flushPara(i)
                blocks.add(MdBlock.Hr(raw = line, startLine = i, endLine = i + 1))
                i++
                continue
            }

            // 表格：当前行含 | 且下一行是分隔行
            if (trimmed.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1].trim())) {
                flushPara(i)
                val from = i
                val header = splitRow(trimmed)
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].trim().contains('|') && lines[i].trim().isNotEmpty()) {
                    rows.add(splitRow(lines[i].trim()))
                    i++
                }
                blocks.add(
                    MdBlock.Table(
                        header, rows,
                        raw = rawOf(from, i), startLine = from, endLine = i
                    )
                )
                continue
            }

            // 引用
            if (trimmed.startsWith(">")) {
                flushPara(i)
                val from = i
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quote.append(lines[i].trim().removePrefix(">").trim()).append(' ')
                    i++
                }
                val quoteText = quote.toString().trim()
                blocks.add(
                    MdBlock.Quote(
                        quoteText,
                        runs = parseInline(quoteText),
                        raw = rawOf(from, i), startLine = from, endLine = i
                    )
                )
                continue
            }

            // 列表（有序/无序/任务，按缩进分层）
            val item = parseListItem(line)
            if (item != null) {
                flushPara(i)
                val from = i
                val items = ArrayList<MdItem>()
                var idx = i
                while (idx < lines.size) {
                    val it = parseListItem(lines[idx]) ?: break
                    items.add(it.copy(runs = parseInline(it.text)))
                    idx++
                }
                blocks.add(
                    MdBlock.ListBlock(
                        items,
                        raw = rawOf(from, idx), startLine = from, endLine = idx
                    )
                )
                i = idx
                continue
            }

            // 空行 → 段落分隔
            if (trimmed.isEmpty()) {
                flushPara(i)
                i++
                continue
            }

            if (para.isEmpty()) paraStart = i
            if (para.isNotEmpty()) para.append(' ')
            para.append(trimmed)
            i++
        }
        flushPara(lines.size)
        return blocks
    }

    /** 从标题列表生成目录（level 1..3） */
    fun outline(blocks: List<MdBlock>): List<Pair<Int, String>> =
        blocks.filterIsInstance<MdBlock.Heading>().filter { it.level <= 3 }.map { it.level to it.text }

    // ---------- 行内解析 ----------

    fun parseInline(text: String): List<MdRun> {
        val runs = ArrayList<MdRun>()
        val plain = StringBuilder()
        var i = 0

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                runs.add(MdRun.Plain(plain.toString()))
                plain.setLength(0)
            }
        }

        while (i < text.length) {
            val rest = text.substring(i)
            when {
                rest.startsWith("**") -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) { flushPlain(); runs.add(MdRun.Bold(text.substring(i + 2, end))); i = end + 2; continue }
                }
                rest.startsWith("~~") -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i) { flushPlain(); runs.add(MdRun.Strike(text.substring(i + 2, end))); i = end + 2; continue }
                }
                rest.startsWith("`") -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) { flushPlain(); runs.add(MdRun.Code(text.substring(i + 1, end))); i = end + 1; continue }
                }
                rest.startsWith("*") || rest.startsWith("_") -> {
                    val marker = rest[0]
                    val end = text.indexOf(marker, i + 1)
                    if (end > i) { flushPlain(); runs.add(MdRun.Italic(text.substring(i + 1, end))); i = end + 1; continue }
                }
                rest.startsWith("[") -> {
                    val close = text.indexOf(']', i + 1)
                    if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                        val urlEnd = text.indexOf(')', close + 2)
                        if (urlEnd > close) {
                            flushPlain()
                            runs.add(MdRun.Link(text.substring(i + 1, close), text.substring(close + 2, urlEnd)))
                            i = urlEnd + 1
                            continue
                        }
                    }
                }
            }
            plain.append(text[i])
            i++
        }
        flushPlain()
        return runs
    }

    // ---------- 辅助 ----------

    private fun headingLevel(trimmed: String): Int {
        if (!trimmed.startsWith("#")) return 0
        var n = 0
        while (n < trimmed.length && trimmed[n] == '#') n++
        return if (n in 1..6 && n < trimmed.length && trimmed[n] == ' ') n else 0
    }

    private fun isHr(trimmed: String): Boolean {
        if (trimmed.length < 3) return false
        val c = trimmed[0]
        if (c != '-' && c != '*' && c != '_') return false
        return trimmed.all { it == c || it == ' ' } && trimmed.count { it == c } >= 3
    }

    private fun isTableSeparator(trimmed: String): Boolean {
        if (!trimmed.contains('-') || !trimmed.contains('|')) return false
        return trimmed.all { it == '|' || it == '-' || it == ':' || it == ' ' }
    }

    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /**
     * 列表项解析（**不使用正则**：Kotlin/JS 会把正则编译成 unicode 模式，
     * 形如 `[([ xX])]` 的字符类会抛 "Lone quantifier brackets" → 解析中断、正文空白）。
     */
    private fun parseListItem(raw: String): MdItem? {
        val indent = raw.takeWhile { it == ' ' || it == '\t' }.length / 2
        val t = raw.trim()
        if (t.length < 2) return null
        val marker = t[0]
        val isBullet = marker == '-' || marker == '*' || marker == '+'
        if (isBullet && t[1] == ' ') {
            // 任务列表：- [ ] / - [x] / - [X]
            if (t.length >= 6 && t[2] == '[' && t[4] == ']' && t[5] == ' ') {
                val c = t[3]
                if (c == ' ' || c == 'x' || c == 'X') {
                    return MdItem(
                        text = t.substring(6),
                        ordered = false, index = 0, indent = indent,
                        task = true, checked = c == 'x' || c == 'X'
                    )
                }
            }
            return MdItem(t.substring(2), ordered = false, index = 0, indent = indent)
        }
        // 有序：1. xxx / 1) xxx
        if (marker.isDigit()) {
            var i = 0
            while (i < t.length && t[i].isDigit()) i++
            if (i in 1..9 && i + 1 < t.length && (t[i] == '.' || t[i] == ')') && t[i + 1] == ' ') {
                return MdItem(
                    text = t.substring(i + 2),
                    ordered = true,
                    index = t.substring(0, i).toIntOrNull() ?: 1,
                    indent = indent
                )
            }
        }
        return null
    }
}
