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
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * PDF 预览（§4.6）
 *
 * - **Phase 0.4 占位**：显示「待 Phase 1 接入原生 PDF 渲染」提示
 * - Phase 1 思路：
 *   - Android：下载到本地缓存 → `PdfRenderer` 渲染位图 → 用 `Image` 展示
 *   - iOS/macOS：`PDFKit` 的 `PDFView` 跨端 View
 *   - HarmonyOS：`@ohos.pdf` 接口或第三方组件
 *   - Web/MiniApp：`<embed>` 或 jsPDF
 * - 超过 50MB 提示「过大，建议下载后查看」
 */
internal fun ViewContainer<*, *>.SftpPdfViewer(
    connectionId: String,
    remotePath: String,
    name: String,
    size: Long
) {
    View {
        attr { flex(1f); backgroundColor(SftpColorTokens.bg); allCenter(); flexDirectionColumn() }
        Text {
            attr {
                text("📄 " + name)
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
                marginBottom(16f)
            }
        }
        Text {
            attr {
                text(I18n.t("sftp.viewer.pdf_phase_hint"))
                fontSize(13f)
                color(SftpColorTokens.textSecondary)
            }
        }
        if (size > 52_428_800L) {
            View {
                attr { marginTop(12f); padding(8f, 12f, 8f, 12f); backgroundColor(SftpColorTokens.divider); borderRadius(8f) }
                Text {
                    attr {
                        text(I18n.t("sftp.viewer.pdf_too_large"))
                        fontSize(12f)
                        color(SftpColorTokens.danger)
                    }
                }
            }
        }
    }
}
