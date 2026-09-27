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

import com.tencent.kuikly.core.directives.vforLazy
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.ListView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * 纯文本渲染（阅读器形态，参考 VS Code / Monaco 的只读查看）：
 * 行号槽 + 等宽正文 + 换行开关 + 字号缩放。
 *
 * **虚拟列表**：作为 `ListView` 的扩展用 `vforLazy` 只物化视口附近的若干行并循环复用，
 * 大文件（数千/数万行）拉到末尾也不会累积成上万个视图（修复卡顿/滚轮失效/空白）。
 *
 * 响应式约定：字号/换行等可变状态均以 provider 传入并在 `attr {}` 内读取
 * （结构层读取不会被依赖收集 → 缩放/切换不生效）。
 */
internal fun ListView<*, *>.SftpTextViewer(
    linesProvider: () -> ObservableList<String>,
    fontScaleProvider: () -> Float,
    wrapProvider: () -> Boolean,
) {
    // maxLoadItem 必须大于「视口能显示的条目数 / (1 - 1/3)」，否则列表尾部几条会因窗口
    // 只覆盖 firstVisible 之后的 2/3 而永远物化不到（表现为拉到底仍看不到最后几行）。
    // 实测：19px 行高 + ~505px 视口 ≈ 27 行 → 需要 ≳41；这里取 150 留足余量（含缩放/大窗口）。
    vforLazy({ linesProvider() }, maxLoadItem = 150) { line, index, _ ->
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
