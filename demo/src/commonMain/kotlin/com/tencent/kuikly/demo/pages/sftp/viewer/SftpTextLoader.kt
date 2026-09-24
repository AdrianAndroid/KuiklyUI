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

import com.tencent.kuikly.core.log.KLog
import com.tencent.kuikly.core.module.sftp.SftpModule

/** 文本装载结果 */
internal data class TextContent(
    val text: String,
    val encoding: String,
    val byteCount: Long,
    /** 超过上限被截断（只读了前 [MAX_BYTES]） */
    val truncated: Boolean
)

/**
 * 文本装载器：分块流式读取 + 跨端解码。
 *
 * - 纯 commonMain（无平台 API），Web/Android/iOS/OHOS 共用
 * - 分块 96KB，超过 [MAX_BYTES] 截断（避免超大文件卡住渲染）
 * - 支持 BOM（UTF-8 / UTF-16LE / UTF-16BE）与 UTF-8 多字节（含 emoji 4 字节）
 * - 非 UTF-8 且无 BOM 时按 UTF-8 尽力解码（无法解码的字节以 U+FFFD 呈现）
 */
internal object SftpTextLoader {

    const val MAX_BYTES = 2L * 1024L * 1024L
    private const val CHUNK = 96 * 1024

    fun load(
        sftp: SftpModule,
        sessionId: String,
        remotePath: String,
        sizeHint: Long,
        onDone: (content: TextContent?, error: String?) -> Unit
    ) {
        KLog.i("SftpTextLoader", "load start path=$remotePath size=$sizeHint")
        sftp.openRead(sessionId, remotePath) { handleId, err ->
            if (handleId == null) {
                onDone(null, err?.msg ?: "无法打开文件")
                return@openRead
            }
            val cap = if (sizeHint in 1 until MAX_BYTES) sizeHint else MAX_BYTES
            val chunks = ArrayList<ByteArray>()
            var total = 0L

            fun finish() {
                sftp.close(handleId, null)
                val bytes = concat(chunks, total)
                val (text, encoding) = decode(bytes)
                onDone(
                    TextContent(
                        text = text,
                        encoding = encoding,
                        byteCount = total,
                        truncated = total >= cap && (sizeHint > MAX_BYTES || sizeHint <= 0L && total >= cap)
                    ),
                    null
                )
            }

            fun readNext() {
                if (total >= cap) {
                    finish()
                    return
                }
                val want = minOf(CHUNK.toLong(), cap - total).toInt()
                sftp.read(handleId, total, want) { bytes, readErr ->
                    if (readErr != null) {
                        onDone(null, readErr.msg)
                        return@read
                    }
                    if (bytes == null || bytes.isEmpty()) {
                        finish()
                        return@read
                    }
                    chunks.add(bytes)
                    total += bytes.size
                    readNext()
                }
            }
            readNext()
        }
    }

    private fun concat(chunks: List<ByteArray>, total: Long): ByteArray {
        val out = ByteArray(total.toInt())
        var pos = 0
        chunks.forEach { c ->
            c.copyInto(out, pos)
            pos += c.size
        }
        return out
    }

    // ---------- 解码（纯 Kotlin，全端一致） ----------

    fun decode(bytes: ByteArray): Pair<String, String> {
        if (bytes.isEmpty()) return "" to "UTF-8"
        // BOM 探测
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return decodeUtf8(bytes, 3) to "UTF-8(BOM)"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return decodeUtf16(bytes, littleEndian = true) to "UTF-16LE"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return decodeUtf16(bytes, littleEndian = false) to "UTF-16BE"
        }
        return decodeUtf8(bytes, 0) to "UTF-8"
    }

    // ---------- 编码（保存用；纯 Kotlin，全端一致） ----------

    /** UTF-8 编码（含代理对 → 4 字节） */
    fun encodeUtf8(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length * 3)
        for (ch in text) {
            val c = ch.code
            when {
                c < 0x80 -> out.add(c.toByte())
                c < 0x800 -> {
                    out.add((0xC0 or (c shr 6)).toByte())
                    out.add((0x80 or (c and 0x3F)).toByte())
                }
                c in 0xD800..0xDBFF -> {
                    // 高代理：与后续低代理组成码点
                    val next = text.indexOf(ch) + 1
                    val lo = if (next < text.length) text[next].code else 0
                    if (lo in 0xDC00..0xDFFF) {
                        val cp = 0x10000 + ((c - 0xD800) shl 10) + (lo - 0xDC00)
                        out.add((0xF0 or (cp shr 18)).toByte())
                        out.add((0x80 or ((cp shr 12) and 0x3F)).toByte())
                        out.add((0x80 or ((cp shr 6) and 0x3F)).toByte())
                        out.add((0x80 or (cp and 0x3F)).toByte())
                    }
                }
                else -> {
                    out.add((0xE0 or (c shr 12)).toByte())
                    out.add((0x80 or ((c shr 6) and 0x3F)).toByte())
                    out.add((0x80 or (c and 0x3F)).toByte())
                }
            }
        }
        return ByteArray(out.size) { out[it] }
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** 标准 base64（保存文本时经宿主/网关传字节） */
    fun base64(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() and 0xFF)
            sb.append(B64[(n shr 18) and 0x3F]).append(B64[(n shr 12) and 0x3F])
                .append(B64[(n shr 6) and 0x3F]).append(B64[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                sb.append(B64[(n shr 18) and 0x3F]).append(B64[(n shr 12) and 0x3F]).append("==")
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                sb.append(B64[(n shr 18) and 0x3F]).append(B64[(n shr 12) and 0x3F])
                    .append(B64[(n shr 6) and 0x3F]).append('=')
            }
        }
        return sb.toString()
    }

    private fun decodeUtf16(bytes: ByteArray, littleEndian: Boolean): String {
        val sb = StringBuilder(bytes.size / 2)
        var i = 2
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt() and 0xFF
            val unit = if (littleEndian) (hi shl 8) or lo else (lo shl 8) or hi
            sb.append(unit.toChar())
            i += 2
        }
        return sb.toString()
    }

    /** 标准 UTF-8 解码：1~4 字节，4 字节用代理对表示（emoji 等） */
    fun decodeUtf8(bytes: ByteArray, start: Int): String {
        val sb = StringBuilder(bytes.size)
        var i = start
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            when {
                b0 < 0x80 -> {
                    sb.append(b0.toChar()); i++
                }
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
                    // 转 UTF-16 代理对
                    val v = cp - 0x10000
                    sb.append(((v shr 10) + 0xD800).toChar())
                    sb.append(((v and 0x3FF) + 0xDC00).toChar())
                    i += 4
                }
                else -> {
                    sb.append('\uFFFD'); i++
                }
            }
        }
        return sb.toString()
    }
}
