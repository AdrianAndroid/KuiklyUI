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
import com.tencent.kuikly.core.directives.vforIndex
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * 纯文本渲染（阅读器形态，参考 VS Code / Monaco 的只读查看）：
 * 行号槽 + 等宽正文 + 换行开关 + 字号缩放。
 *
 * **增量渲染（窗口）**：不再一次建出上千行视图（旧实现硬上限 1500 行，既卡顿又看不全），
 * 改为按窗口（`ObservableList`）渲染已加载行，底部「点此加载更多」逐批追加，
 * 直至覆盖全文 —— 既能快速切换源码/预览，也能真正拉到文档末尾。
 *
 * 响应式约定：字号/换行等可变状态均以 provider 传入并在 `attr {}` 内读取
 * （结构层读取不会被依赖收集 → 缩放/切换不生效）。
 */
internal fun ViewContainer<*, *>.SftpTextViewer(
    /** 已渲染窗口（ObservableList）：vforIndex 只渲染窗口内的行，追加时只建新增行 */
    visibleLinesProvider: () -> ObservableList<String>,
    /** 全文行数（用于「已渲染 X/Y 行」与是否还有剩余） */
    totalLinesProvider: () -> Int,
    fontScaleProvider: () -> Float,
    wrapProvider: () -> Boolean,
    truncatedProvider: () -> Boolean,
    /** 追加一批行（点底部提示；原生端若上报滚动偏移也会自动触发） */
    onLoadMore: (() -> Unit)? = null,
) {
    vif({ visibleLinesProvider().isNotEmpty() }) {
        SftpTextBody(
            visibleLinesProvider, totalLinesProvider,
            fontScaleProvider, wrapProvider, truncatedProvider, onLoadMore
        )
    }
    velse {
        View {
            attr { flex(1f); allCenter() }
            Text { attr { text("(空文件)"); fontSize(13f); color(SftpColorTokens.textSecondary) } }
        }
    }
}

private fun ViewContainer<*, *>.SftpTextBody(
    visibleLinesProvider: () -> ObservableList<String>,
    totalLinesProvider: () -> Int,
    fontScaleProvider: () -> Float,
    wrapProvider: () -> Boolean,
    truncatedProvider: () -> Boolean,
    onLoadMore: (() -> Unit)?,
) {
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            backgroundColor(SftpColorTokens.cardBg)
            borderRadius(8f)
            padding(10f, 8f, 10f, 8f)
        }
        // 仅当读取阶段就超上限（> SftpTextLoader.MAX_BYTES）才提示内容缺失；
        // 行渲染窗口是可增量加载的，不再用「仅渲染前 N 行」这种永久截断提示。
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
        // 行号为窗口前缀下标（窗口始终从第 0 行开始），故 index+1 始终是真实行号
        vforIndex({ visibleLinesProvider() }) { line, index, _ ->
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
        // 增量渲染占位：还有未渲染的行时给出提示（点按或原生端滚动自动追加）
        vif({ totalLinesProvider() > visibleLinesProvider().size }) {
            View {
                attr { height(36f); allCenter() }
                if (onLoadMore != null) { event { click { onLoadMore.invoke() } } }
                Text {
                    attr {
                        text(
                            if (onLoadMore != null) {
                                "点此加载更多 · 已渲染 ${visibleLinesProvider().size}/${totalLinesProvider()} 行"
                            } else {
                                "已渲染 ${visibleLinesProvider().size}/${totalLinesProvider()} 行"
                            }
                        )
                        fontSize(11f)
                        color(SftpColorTokens.textSecondary)
                        accessibility("text_more")
                    }
                }
            }
        }
    }
}
