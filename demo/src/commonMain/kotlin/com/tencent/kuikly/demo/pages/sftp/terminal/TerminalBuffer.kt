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
package com.tencent.kuikly.demo.pages.sftp.terminal

/**
 * 跨端终端缓冲区（纯 Kotlin，commonMain 全端共用）。
 *
 * 定位：**native 端的终端渲染不依赖任何平台 UI**——这里把 SSH/本地 shell 的字节流
 * 解析成 `rows × cols` 的字符网格，再用 Kuikly 的等宽文本渲染（见 TerminalGridView）。
 * Web/桌面可选 xterm.js 加速（性能更好），两者共用同一份 shell 通道（Module 契约）。
 *
 * 支持（ANSI-lite，覆盖交互式 shell 常见序列）：
 * - 可打印字符、中文/宽字符占两格、\n \r \b \t \u0007
 * - CSI: A/B/C/D 光标移动、H/f 绝对定位、G 列定位、J 清屏(0/1/2)、K 清行(0/1/2)、m 忽略(SGR)
 * - 退格、回车覆盖、自动换行、滚动
 * - 忽略其余 ESC 序列（不崩、不乱码）
 */
internal class TerminalBuffer(val cols: Int = 80, val rows: Int = 24) {

    private val grid: Array<CharArray> = Array(rows) { CharArray(cols) { ' ' } }
    var cx: Int = 0
        private set
    var cy: Int = 0
        private set

    /** 内容变化计数（供渲染层判断是否需要刷新） */
    var version: Int = 0
        private set

    private var state = 0 // 0=普通 1=ESC 2=CSI
    private val csi = StringBuilder()

    fun resize(newCols: Int, newRows: Int) {
        // 简化处理：尺寸变化时清屏重画（交互式 shell 会自行重绘）
        if (newCols == cols && newRows == rows) return
        version++
    }

    fun line(row: Int): String = if (row in 0 until rows) grid[row].concatToString() else ""

    fun allLines(): List<String> = (0 until rows).map { line(it) }

    /** 光标所在行（供"输入行"渲染高亮） */
    fun cursorRow(): Int = cy

    fun feed(bytes: ByteArray) {
        val text = decodeUtf8(bytes)
        feed(text)
    }

    fun feed(text: String) {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when (state) {
                0 -> when (ch) {
                    '\u001B' -> state = 1
                    '\n' -> { cy++; cx = 0; scrollIfNeeded() }
                    '\r' -> cx = 0
                    '\b' -> if (cx > 0) cx--
                    '\t' -> cx = ((cx / 8) + 1) * 8
                    '\u0007' -> { /* bell 忽略 */ }
                    else -> if (ch.code >= 0x20) putChar(ch)
                }
                1 -> when (ch) {
                    '[' -> { state = 2; csi.setLength(0) }
                    ']' -> state = 3          // OSC：忽略到 BEL
                    else -> state = 0
                }
                2 -> if (ch in '@'..'~') {
                    applyCsi(ch, csi.toString())
                    state = 0
                } else {
                    csi.append(ch)
                }
                3 -> if (ch == '\u0007') state = 0   // OSC 结束
            }
            // 宽字符（CJK 等）占两格
            i += if (ch.code in 0x1100..0xFFE6 && isWide(ch)) 1 else 1
            if (cx >= cols) { cx = 0; cy++; scrollIfNeeded() }
        }
        version++
    }

    private fun putChar(ch: Char) {
        if (cy !in 0 until rows) return
        if (cx !in 0 until cols) return
        grid[cy][cx] = ch
        cx++
        if (isWide(ch) && cx < cols) {
            grid[cy][cx] = ' '        // 宽字符占位（渲染为空格，保持对齐）
            cx++
        }
    }

    private fun isWide(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x1100..0x115F) || (c in 0x2E80..0xA4CF) || (c in 0xAC00..0xD7A3) ||
            (c in 0xF900..0xFAFF) || (c in 0xFE30..0xFE4F) || (c in 0xFF00..0xFF60) ||
            (c in 0xFFE0..0xFFE6)
    }

    private fun scrollIfNeeded() {
        while (cy >= rows) {
            for (r in 1 until rows) {
                grid[r - 1].copyInto(grid[r])
            }
            grid[rows - 1].fill(' ')
            cy = rows - 1
        }
    }

    private fun applyCsi(letter: Char, argsRaw: String) {
        // 形如 "12;34"；私有前缀 ? 直接忽略
        val clean = argsRaw.trimStart('?', '>', '!')
        val args = clean.split(';').mapNotNull { it.toIntOrNull() }
        val n0 = args.getOrNull(0) ?: 0
        when (letter) {
            'A' -> cy = (cy - maxOf(1, n0)).coerceAtLeast(0)
            'B' -> { cy = (cy + maxOf(1, n0)).coerceAtMost(rows - 1) }
            'C' -> cx = (cx + maxOf(1, n0)).coerceAtMost(cols - 1)
            'D' -> cx = (cx - maxOf(1, n0)).coerceAtLeast(0)
            'E' -> { cy = (cy + maxOf(1, n0)).coerceAtMost(rows - 1); cx = 0 }
            'F' -> { cy = (cy - maxOf(1, n0)).coerceAtLeast(0); cx = 0 }
            'G' -> cx = (maxOf(1, n0) - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                val row = args.getOrNull(0) ?: 1
                val col = args.getOrNull(1) ?: 1
                cy = (row - 1).coerceIn(0, rows - 1)
                cx = (col - 1).coerceIn(0, cols - 1)
            }
            'J' -> when (n0) {
                0 -> { for (c in cx until cols) grid[cy][c] = ' '; for (r in cy + 1 until rows) grid[r].fill(' ') }
                1 -> { for (c in 0..cx.coerceAtMost(cols - 1)) grid[cy][c] = ' '; for (r in 0 until cy) grid[r].fill(' ') }
                else -> { grid.forEach { it.fill(' ') }; cx = 0; cy = 0 }
            }
            'K' -> when (n0) {
                0 -> for (c in cx until cols) grid[cy][c] = ' '
                1 -> for (c in 0..cx.coerceAtMost(cols - 1)) grid[cy][c] = ' '
                else -> grid[cy].fill(' ')
            }
            'm' -> { /* SGR：颜色/加粗暂不渲染（保持等宽单色） */ }
            else -> { /* 其余忽略 */ }
        }
    }

    /** 供输入行渲染使用：取最后一行的非空文本 */
    fun lastNonEmptyLine(): String = (rows - 1 downTo 0).firstOrNull { line(it).isNotBlank() }?.let { line(it) } ?: ""

    // ---------- UTF-8 解码（与 SftpTextLoader 一致的最小实现） ----------
    private fun decodeUtf8(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            when {
                b0 < 0x80 -> { sb.append(b0.toChar()); i++ }
                b0 and 0xE0 == 0xC0 && i + 1 < bytes.size -> {
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    sb.append((((b0 and 0x1F) shl 6) or (b1 and 0x3F)).toChar()); i += 2
                }
                b0 and 0xF0 == 0xE0 && i + 2 < bytes.size -> {
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    val b2 = bytes[i + 2].toInt() and 0xFF
                    sb.append((((b0 and 0x0F) shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F)).toChar()); i += 3
                }
                b0 and 0xF8 == 0xF0 && i + 3 < bytes.size -> {
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    val b2 = bytes[i + 2].toInt() and 0xFF
                    val b3 = bytes[i + 3].toInt() and 0xFF
                    val cp = ((b0 and 0x07) shl 18) or ((b1 and 0x3F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)
                    val v = cp - 0x10000
                    sb.append(((v shr 10) + 0xD800).toChar())
                    sb.append(((v and 0x3FF) + 0xDC00).toChar())
                    i += 4
                }
                else -> { sb.append('\uFFFD'); i++ }
            }
        }
        return sb.toString()
    }
}
