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
import com.tencent.kuikly.core.module.sftp.SftpMediaUrlBuilder
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * 图片预览（§4.5）
 *
 * - 本地代理 URL 由页面异步申请后传入（组件只渲染）
 * - 支持 jpg / png / gif / webp / bmp / svg
 * - SVG 走特殊路径：Phase 1 接入 `SvgView` 跨端组件
 * - 支持双指缩放手势（Phase 1）
 */
internal fun ViewContainer<*, *>.SftpImageViewer(
    mediaUrlProvider: () -> String?,
    fileNameProvider: () -> String
) {
    View {
        attr { flex(1f); backgroundColor(SftpColorTokens.bg); allCenter() }
        // 只负责渲染：代理端口/token 的申请由页面（Pager）异步完成后传入。
        // 组件内同步调代理是错误分层，且拿不到真实 token（预览会一直是坏的）。
        val url = mediaUrlProvider()
        if (url.isNullOrEmpty()) {
            Text {
                attr {
                    text(I18n.t("sftp.ui.loading"))
                    fontSize(14f)
                    color(SftpColorTokens.textSecondary)
                }
            }
        } else {
            Image {
                attr {
                    src(url)
                    // Phase 1：接入 resizeMode + 双指缩放手势
                }
            }
        }
        // 文件名标注
        View {
            attr { marginTop(16f); allCenter() }
            Text {
                attr {
                    text(fileNameProvider())
                    fontSize(12f)
                    color(SftpColorTokens.textSecondary)
                }
            }
        }
        View {
            attr { marginTop(4f); allCenter() }
            Text {
                attr {
                    text(I18n.t("sftp.viewer.image_phase_hint"))
                    fontSize(11f)
                    color(SftpColorTokens.textSecondary)
                }
            }
        }
    }
}
