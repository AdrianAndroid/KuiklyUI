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
import com.tencent.kuikly.core.module.sftp.I18n
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * 文本预览（§4.2）
 *
 * - 已通过 [com.tencent.kuikly.demo.pages.sftp.viewer.SftpViewerDispatcherPage] 拉头部 8KB 检测编码
 * - Phase 0.4 简化：只渲染已拉的头部 8KB；Phase 1 流式渲染全文（分块 read）
 * - 编码检测：UTF-8 / UTF-16 / GBK / Latin-1
 * - 大于 1MB 显示警告
 */
internal fun ViewContainer<*, *>.SftpTextViewer(
    sessionId: String,
    remotePath: String,
    size: Long,
    encoding: String,
    headBytes: ByteArray?
) {
    View {
        attr { flex(1f); backgroundColor(SftpColorTokens.bg); padding(16f, 16f, 16f, 16f) }
        // 文件元信息
        View {
            attr { flexDirectionRow(); marginBottom(12f) }
            Text {
                attr {
                    text(I18n.t("sftp.viewer.text_encoding") + ": " + encoding + " · " + I18n.t("sftp.viewer.text_size") + ": " + size + "B")
                    fontSize(12f)
                    color(SftpColorTokens.textSecondary)
                }
            }
        }
        // 文本内容（Phase 0.4 简化：只渲染 head）
        val content = decodeUtf8(headBytes)
        Scroller {
            attr { flex(1f); backgroundColor(SftpColorTokens.cardBg); borderRadius(8f); padding(12f, 12f, 12f, 12f) }
            Text {
                attr {
                    text(content)
                    fontSize(13f)
                    color(SftpColorTokens.textPrimary)
                    // 单倍行距
                    lineHeight(18f)
                }
            }
        }
        // 超大文件警告
        if (size > 1_048_576L) {
            View {
                attr { marginTop(8f); allCenter() }
                Text {
                    attr {
                        text(I18n.t("sftp.viewer.text_too_large"))
                        fontSize(12f)
                        color(SftpColorTokens.danger)
                    }
                }
            }
        }
    }
}

/**
 * 简化版 UTF-8 解码（commonMain 无 `String(bytes, Charsets.UTF_8)`）。
 * 仅支持基本 ASCII + 双字节 UTF-8；复杂场景 Phase 1 改用 kotlinx-io。
 */
internal fun decodeUtf8(bytes: ByteArray?): String {
    if (bytes == null) return ""
    val sb = StringBuilder(bytes.size)
    var i = 0
    while (i < bytes.size) {
        val b = bytes[i].toInt() and 0xFF
        if (b < 0x80) {
            sb.append(b.toChar())
            i++
        } else if (b and 0xE0 == 0xC0 && i + 1 < bytes.size) {
            val b2 = bytes[i + 1].toInt() and 0xFF
            val cp = ((b and 0x1F) shl 6) or (b2 and 0x3F)
            sb.append(cp.toChar())
            i += 2
        } else if (b and 0xF0 == 0xE0 && i + 2 < bytes.size) {
            val b2 = bytes[i + 1].toInt() and 0xFF
            val b3 = bytes[i + 2].toInt() and 0xFF
            val cp = ((b and 0x0F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)
            sb.append(cp.toChar())
            i += 3
        } else {
            sb.append('?')
            i++
        }
    }
    return sb.toString()
}
