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
package com.tencent.kuikly.core.module.sftp

/**
 * 文本编码嗅探（§21.6.2）
 *
 * 探测顺序：
 * 1. UTF-8 BOM → UTF-8
 * 2. UTF-16 BOM → UTF-16
 * 3. 检测前 4KB null byte 占比 → 二进制（>1%）拒绝预览（§21.6.1）
 * 4. 尝试 UTF-8 严格解码 → 成功则 UTF-8
 * 5. fallback GBK（Windows 老服务器常见）
 * 6. fallback Latin-1（最后兜底）
 */
object EncodingDetector {

    /** 前 4KB null byte 占比阈值，超过判定为二进制（§21.6.1） */
    const val BINARY_NULL_RATIO_THRESHOLD = 0.01

    /**
     * 嗅探编码
     * @param head 前 4KB 字节
     * @return 编码名（"UTF-8" / "UTF-16" / "GBK" / "Latin-1" / "BINARY"）
     */
    fun detect(head: ByteArray): String {
        if (head.isEmpty()) return "UTF-8"

        // 1. BOM 嗅探
        if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) {
            return "UTF-8"
        }
        if (head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) {
            return "UTF-16"
        }
        if (head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) {
            return "UTF-16"
        }

        // 2. 二进制嗅探（null byte 占比）
        val sampleSize = minOf(head.size, 4096)
        var nullCount = 0
        for (i in 0 until sampleSize) {
            if (head[i] == 0.toByte()) nullCount++
        }
        val nullRatio = nullCount.toDouble() / sampleSize
        if (nullRatio > BINARY_NULL_RATIO_THRESHOLD) {
            return "BINARY"
        }

        // 3. 尝试 UTF-8 严格解码
        if (isStrictUtf8(head)) {
            return "UTF-8"
        }

        // 4. 含中文常见 GBK 高字节范围（0xA1-0xFE）则判定 GBK
        if (looksLikeGbk(head)) {
            return "GBK"
        }

        // 5. fallback Latin-1
        return "Latin-1"
    }

    /** 是否是二进制（前 4KB null byte 占比 > 1%） */
    fun isBinary(head: ByteArray): Boolean = detect(head) == "BINARY"

    /** UTF-8 严格校验：逐字节检查编码规则 */
    private fun isStrictUtf8(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) {
                i++
            } else if (b and 0xE0 == 0xC0) {
                if (i + 1 >= bytes.size) return false
                if (bytes[i + 1].toInt() and 0xC0 != 0x80) return false
                i += 2
            } else if (b and 0xF0 == 0xE0) {
                if (i + 2 >= bytes.size) return false
                if (bytes[i + 1].toInt() and 0xC0 != 0x80) return false
                if (bytes[i + 2].toInt() and 0xC0 != 0x80) return false
                i += 3
            } else if (b and 0xF8 == 0xF0) {
                if (i + 3 >= bytes.size) return false
                if (bytes[i + 1].toInt() and 0xC0 != 0x80) return false
                if (bytes[i + 2].toInt() and 0xC0 != 0x80) return false
                if (bytes[i + 3].toInt() and 0xC0 != 0x80) return false
                i += 4
            } else {
                return false
            }
        }
        return true
    }

    /** 启发式判定 GBK：高字节落在 0xA1-0xFE 且配对出现 */
    private fun looksLikeGbk(bytes: ByteArray): Boolean {
        val sample = minOf(bytes.size, 4096)
        var gbkPairs = 0
        var i = 0
        while (i < sample - 1) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 0xA1..0xFE) {
                val b2 = bytes[i + 1].toInt() and 0xFF
                if (b2 in 0x40..0xFE && b2 != 0x7F) {
                    gbkPairs++
                    i += 2
                    continue
                }
            }
            i++
        }
        // 至少 2 对 GBK 双字节才判定
        return gbkPairs >= 2
    }
}
