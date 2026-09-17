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
import com.tencent.kuikly.core.module.sftp.LocalMediaProxyApi
import com.tencent.kuikly.core.module.sftp.SftpMediaUrlBuilder
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * 图片预览（§4.5）
 *
 * - 通过 [LocalMediaProxyApi] 拿本地代理 URL → [Image] 加载
 * - 支持 jpg / png / gif / webp / bmp / svg
 * - SVG 走特殊路径：Phase 1 接入 `SvgView` 跨端组件
 * - 支持双指缩放手势（Phase 1）
 */
internal fun ViewContainer<*, *>.SftpImageViewer(
    sessionId: String,
    remotePath: String,
    connectionId: String
) {
    View {
        attr { flex(1f); backgroundColor(SftpColorTokens.bg); allCenter() }
        // 注册代理 token，加载图片
        val proxy = LocalMediaProxyApi.getInstance()
        val port = proxy.startOrGetPort()
        val sftpSessionId = sessionId.ifEmpty { connectionId }  // 降级：旧链路可能未传 sessionId
        val token = proxy.registerToken(sftpSessionId, remotePath, 0L)
        val url = SftpMediaUrlBuilder.buildPlayUrl(port, token, remotePath.substringAfterLast('/'))

        Image {
            attr {
                src(url)
                // 缩放模式：等比例适应
                // Phase 1：接入 resizeMode + 双指缩放手势
            }
        }
        // 文件名标注
        View {
            attr { marginTop(16f); allCenter() }
            Text {
                attr {
                    text(remotePath.substringAfterLast('/'))
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
