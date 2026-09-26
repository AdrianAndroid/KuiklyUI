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
 * 阅读器外壳（对齐 Vditor 的 `toolbarConfig.pin` 思路：**单行、置顶、紧凑**）。
 *
 * 一行内：字号 A−/A+ · 换行 · 源码/预览 · 编辑/完成 · 目录(n) · 保存*（dirty 时显示 *）
 * 右侧同一行显示状态（编码 · 大小 · 行数 · 字数），不再单独占一行。
 */
internal fun ViewContainer<*, *>.SftpReaderScaffold(
    metaProvider: () -> String,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    wrapProvider: () -> Boolean,
    onToggleWrap: () -> Unit,
    onScrollerReady: (scrollTo: (Float) -> Unit) -> Unit,
    /** 滚动回调（传当前 contentOffsetY）：用于增量渲染，避免一次性建出全部块视图 */
    onScroll: ((Float) -> Unit)? = null,
    mdSourceProvider: (() -> Boolean)? = null,
    onToggleSource: (() -> Unit)? = null,
    tocCountProvider: (() -> Int)? = null,
    onToggleToc: (() -> Unit)? = null,
    editingProvider: (() -> Boolean)? = null,
    onToggleEditing: (() -> Unit)? = null,
    dirtyProvider: (() -> Boolean)? = null,
    onSave: (() -> Unit)? = null,
    saveMsgProvider: (() -> String)? = null,
    content: ViewBuilder,
) {
    View {
        attr { flex(1f); flexDirectionColumn() }

        // 单行工具条（置顶、紧凑）
        View {
            attr {
                flexDirectionRow()
                alignItemsCenter()
                padding(8f, 3f, 8f, 3f)
                backgroundColor(SftpColorTokens.cardBg)
            }
            ReaderChip({ "A−" }, onZoomOut)
            ReaderChip({ "A+" }, onZoomIn)
            ReaderChip({ if (wrapProvider()) "换行" else "不换行" }, onToggleWrap)
            if (mdSourceProvider != null && onToggleSource != null) {
                ReaderChip({ if (mdSourceProvider()) "预览" else "源码" }, onToggleSource)
            }
            if (editingProvider != null && onToggleEditing != null) {
                ReaderChip({ if (editingProvider()) "完成" else "编辑" }, onToggleEditing)
            }
            if (onToggleToc != null && tocCountProvider != null) {
                vif({ tocCountProvider() > 0 }) {
                    ReaderChip({ "目录 ${tocCountProvider()}" }, onToggleToc)
                }
            }
            if (onSave != null && dirtyProvider != null) {
                ReaderChip({ if (dirtyProvider()) "保存*" else "保存" }, onSave)
            }
            // 状态占满剩余空间（同一行，不再另起一行）
            Text {
                attr {
                    text(metaProvider())
                    fontSize(10.5f)
                    color(SftpColorTokens.textSecondary)
                    flex(1f)
                    marginLeft(6f)
                    lines(1)
                }
            }
        }

        // 保存结果提示（有才显示，占一行很薄）
        if (saveMsgProvider != null) {
            vif({ saveMsgProvider().isNotEmpty() }) {
                Text {
                    attr {
                        text(saveMsgProvider())
                        fontSize(11f)
                        color(SftpColorTokens.primary)
                        margin(10f, 2f, 10f, 2f)
                    }
                }
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
            if (onScroll != null) {
                event {
                    scroll { onScroll.invoke(it.offsetY) }
                }
            }
            content()
        }
    }
}

private fun ViewContainer<*, *>.ReaderChip(labelProvider: () -> String, onClick: () -> Unit) {
    // Text 不支持 padding：胶囊样式用外层 View 承载
    View {
        attr {
            backgroundColor(SftpColorTokens.bg)
            borderRadius(6f)
            padding(6f, 4f, 6f, 4f)
            margin(3f, 3f, 3f, 3f)
            allCenter()
        }
        event { click { onClick() } }
        Text {
            attr {
                text(labelProvider())
                fontSize(12f)
                color(SftpColorTokens.textPrimary)
                lines(1)
            }
        }
    }
}
