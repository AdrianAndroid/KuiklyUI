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
import com.tencent.kuikly.core.module.sftp.I18n
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * Markdown 预览（§4.3 / §17.3.3.3）
 *
 * - **Phase 0.4 简化**：先用纯文本展示（待 Phase 1+ 接入 `com.tencent.kuiklybase:markdown` Compose 组件）
 * - 思路：Phase 1 改造为 Compose DSL Page（`ComposeContainer + setContent{}`），
 *   commonMain 侧 SftpViewerDispatcher 检测到 Markdown 时跳转到该 Compose Page
 * - 拉取内容：流式 openRead + read + 拼接（Phase 0.4 仅显示提示）
 */
internal fun ViewContainer<*, *>.SftpMarkdownViewer(
    sessionId: String,
    remotePath: String,
    size: Long,
    encoding: String
) {
    View {
        attr { flex(1f); backgroundColor(SftpColorTokens.bg); padding(16f, 16f, 16f, 16f) }
        View {
            attr {
                backgroundColor(SftpColorTokens.cardBg); borderRadius(8f); padding(16f, 12f, 16f, 12f)
                flexDirectionColumn()
            }
            Text {
                attr {
                    text("Markdown 预览")
                    fontSize(16f)
                    fontWeightBold()
                    color(SftpColorTokens.textPrimary)
                    marginBottom(8f)
                }
            }
            Text {
                attr {
                    text("${I18n.t("sftp.viewer.text_encoding")}: $encoding · ${I18n.t("sftp.viewer.text_size")}: ${size}B")
                    fontSize(12f)
                    color(SftpColorTokens.textSecondary)
                    marginBottom(12f)
                }
            }
            Text {
                attr {
                    text(I18n.t("sftp.viewer.markdown_phase_hint"))
                    fontSize(13f)
                    color(SftpColorTokens.textSecondary)
                }
            }
        }
        Scroller {
            attr { flex(1f); backgroundColor(SftpColorTokens.cardBg); borderRadius(8f); padding(12f, 12f, 12f, 12f); marginTop(12f) }
            Text {
                attr {
                    text("# ${remotePath.substringAfterLast('/')}\n\n(Markdown 渲染待 Phase 1 接入 `com.tencent.kuiklybase:markdown`)")
                    fontSize(13f)
                    color(SftpColorTokens.textPrimary)
                    lineHeight(20f)
                }
            }
        }
    }
}
