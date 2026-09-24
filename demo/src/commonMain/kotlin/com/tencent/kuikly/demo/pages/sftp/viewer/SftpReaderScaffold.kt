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

import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * 阅读器外壳：工具条（字号 / 换行 / 源码预览 / 目录）+ 状态栏 + 可滚动内容区。
 *
 * 纯文本与 Markdown 共用；内容由 [content] 传入并放在 [Scroller] 内。
 */
internal fun ViewContainer<*, *>.SftpReaderScaffold(
    metaProvider: () -> String,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    wrapProvider: () -> Boolean,
    onToggleWrap: () -> Unit,
    onScrollerReady: (scrollTo: (Float) -> Unit) -> Unit,
    mdSourceProvider: (() -> Boolean)? = null,
    onToggleSource: (() -> Unit)? = null,
    tocCountProvider: (() -> Int)? = null,
    onToggleToc: (() -> Unit)? = null,
    content: ViewBuilder,
) {
    View {
        attr { flex(1f); flexDirectionColumn() }

        // 工具条
        View {
            attr {
                flexDirectionRow()
                alignItemsCenter()
                padding(10f, 4f, 10f, 4f)
                backgroundColor(SftpColorTokens.bg)
            }
            ReaderChip({ "A−" }, onZoomOut)
            ReaderChip({ "A+" }, onZoomIn)
            // 标签必须用 provider 在 attr 内读：否则开关状态变化不会刷新文案
            ReaderChip({ if (wrapProvider()) "换行:开" else "换行:关" }, onToggleWrap)
            if (mdSourceProvider != null && onToggleSource != null) {
                ReaderChip({ if (mdSourceProvider()) "预览" else "源码" }, onToggleSource)
            }
            if (onToggleToc != null && tocCountProvider != null) {
                // 目录数在结构层读不会被收集 → 用 vif + attr 内读
                vif({ tocCountProvider() > 0 }) {
                    ReaderChip({ "目录(${tocCountProvider()})" }, onToggleToc)
                }
            }
        }

        // 状态栏
        Text {
            attr {
                text(metaProvider())
                fontSize(11f)
                color(SftpColorTokens.textSecondary)
                margin(10f, 0f, 10f, 4f)
            }
        }

        // 内容（可滚动）
        Scroller {
            attr {
                flex(1f)
                flexDirectionColumn()
                padding(10f, 8f, 10f, 8f)
                showScrollerIndicator(true)
            }
            ref { v ->
                onScrollerReady { y -> v.view?.setContentOffset(0f, y, true) }
            }
            content()
        }
    }
}

private fun ViewContainer<*, *>.ReaderChip(labelProvider: () -> String, onClick: () -> Unit) {
    // Text 不支持 padding：胶囊样式用外层 View 承载 padding/背景
    View {
        attr {
            backgroundColor(SftpColorTokens.cardBg)
            borderRadius(6f)
            padding(7f, 6f, 7f, 6f)
            margin(4f, 4f, 4f, 4f)
            allCenter()
        }
        event { click { onClick() } }
        Text { attr { text(labelProvider()); fontSize(12f); color(SftpColorTokens.textPrimary) } }
    }
}
