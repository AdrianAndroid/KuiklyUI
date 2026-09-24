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

import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 终端网格渲染（**六端共用**，纯 Kuikly）：
 * 把 [TerminalBuffer] 的 `rows × cols` 字符网格画成等宽文本行。
 *
 * Web/桌面在有 xterm.js 时用 xterm 加速（性能更好），本视图作为**共享降级实现**，
 * 也是 native 端（Android/iOS/macOS/OHOS）的渲染路径。
 */
internal fun ViewContainer<*, *>.TerminalGridView(
    linesProvider: () -> ObservableList<String>,
    onScrollerReady: (scrollToBottom: () -> Unit) -> Unit,
) {
    Scroller {
        attr {
            flex(1f)
            flexDirectionColumn()
            backgroundColor(Color(0xFF0B0B0B))
            padding(6f, 6f, 6f, 6f)
            showScrollerIndicator(true)
        }
        ref { v ->
            onScrollerReady { v.view?.setContentOffset(0f, 1_000_000f, false) }
        }
        vfor(linesProvider) { line ->
            val shown = if (line is String && line.isEmpty()) " " else line
            Text {
                attr {
                    text(shown)
                    fontSize(11.5f)
                    fontFamily("monospace")
                    color(Color(0xFFE6E6E6))
                    lineHeight(15f)
                }
            }
        }
    }
}
