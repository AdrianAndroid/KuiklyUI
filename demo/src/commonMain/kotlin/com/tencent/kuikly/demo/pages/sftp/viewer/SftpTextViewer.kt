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

import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * 纯文本渲染（阅读器形态，参考 VS Code / Monaco 的只读查看）：
 * 行号槽 + 等宽正文 + 换行开关 + 字号缩放。
 *
 * 响应式约定：字号/换行等可变状态均以 provider 传入并在 `attr {}` 内读取
 * （结构层读取不会被依赖收集 → 缩放/切换不生效）。
 */
internal fun ViewContainer<*, *>.SftpTextViewer(
    linesProvider: () -> List<String>,
    fontScaleProvider: () -> Float,
    wrapProvider: () -> Boolean,
    truncatedProvider: () -> Boolean,
    maxRenderLines: Int = 1500,
) {
    vif({ linesProvider().isNotEmpty() }) {
        SftpTextBody(linesProvider(), fontScaleProvider, wrapProvider, truncatedProvider, maxRenderLines)
    }
    velse {
        View {
            attr { flex(1f); allCenter() }
            Text { attr { text("(空文件)"); fontSize(13f); color(SftpColorTokens.textSecondary) } }
        }
    }
}

private fun ViewContainer<*, *>.SftpTextBody(
    lines: List<String>,
    fontScaleProvider: () -> Float,
    wrapProvider: () -> Boolean,
    truncatedProvider: () -> Boolean,
    maxRenderLines: Int,
) {
    // 渲染上限：超大文件只渲染前 N 行（否则一次创建上万视图会卡死）
    val renderLines = if (lines.size > maxRenderLines) lines.subList(0, maxRenderLines) else lines
    val clipped = lines.size > maxRenderLines
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            backgroundColor(SftpColorTokens.cardBg)
            borderRadius(8f)
            padding(10f, 8f, 10f, 8f)
        }
        vif({ truncatedProvider() }) {
            Text {
                attr {
                    text("⚠ 文件较大，仅显示前一部分内容")
                    fontSize(11f * fontScaleProvider())
                    color(SftpColorTokens.danger)
                    marginBottom(6f)
                }
            }
        }
        if (clipped) {
            Text {
                attr {
                    text("… 仅渲染前 $maxRenderLines 行（共 ${lines.size} 行）")
                    fontSize(11f * fontScaleProvider())
                    color(SftpColorTokens.textSecondary)
                    marginBottom(6f)
                }
            }
        }
        renderLines.forEachIndexed { index, line ->
            View {
                attr { flexDirectionRow(); alignItemsFlexStart() }
                Text {
                    attr {
                        text("${index + 1}")
                        fontSize(11.5f * fontScaleProvider())
                        color(SftpColorTokens.textSecondary)
                        width(46f)
                        textAlignRight()
                        marginRight(8f)
                        lineHeight(19f * fontScaleProvider())
                        fontFamily("monospace")
                    }
                }
                Text {
                    attr {
                        text(line)
                        fontSize(12.5f * fontScaleProvider())
                        color(SftpColorTokens.textPrimary)
                        lineHeight(19f * fontScaleProvider())
                        flex(1f)
                        fontFamily("monospace")
                        if (!wrapProvider()) lines(1)
                    }
                }
            }
        }
    }
}
