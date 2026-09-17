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
 * HTML 预览（§4.4）
 *
 * - **Phase 0.4 简化**：先用纯文本展示源码（待 Phase 1 接入原生 WebView 组件）
 * - 思路：Phase 1 新增 `WebView` 跨端 View（Android `WebView` / iOS `WKWebView` / OHOS `Web`）
 *   + 本地代理 URL（sandbox HTML）+ JS 沙箱（禁 alert/confirm/prompt + 截获 link 跳转）
 * - 预览前必须 `escapeHtml` 并限制最大 256KB
 */
internal fun ViewContainer<*, *>.SftpHtmlViewer(
    sessionId: String,
    remotePath: String,
    size: Long
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
                    text("HTML 预览")
                    fontSize(16f)
                    fontWeightBold()
                    color(SftpColorTokens.textPrimary)
                    marginBottom(8f)
                }
            }
            Text {
                attr {
                    text("${I18n.t("sftp.viewer.text_size")}: ${size}B")
                    fontSize(12f)
                    color(SftpColorTokens.textSecondary)
                    marginBottom(12f)
                }
            }
            Text {
                attr {
                    text(I18n.t("sftp.viewer.html_phase_hint"))
                    fontSize(13f)
                    color(SftpColorTokens.textSecondary)
                }
            }
        }
        Scroller {
            attr { flex(1f); backgroundColor(SftpColorTokens.cardBg); borderRadius(8f); padding(12f, 12f, 12f, 12f); marginTop(12f) }
            Text {
                attr {
                    text("<!-- HTML 源码预览（待 Phase 1 接入 WebView） -->")
                    fontSize(13f)
                    color(SftpColorTokens.textPrimary)
                    lineHeight(18f)
                }
            }
        }
    }
}
