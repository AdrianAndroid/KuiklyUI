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

import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.module.sftp.I18n
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.Video
import com.tencent.kuikly.core.views.VideoPlayControl
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * 音频预览（§4.7）
 *
 * - 复用 [Video] 组件（无画面，只放音频）+ 本地代理 URL
 * - 显示文件名 + 进度条 + 播放控制
 * - 保留播放进度到 [SftpPlaybackHistoryModule]（§19）
 * - 与 [SftpPlayerPage] 共用 [SftpMediaUrlBuilder]
 */
internal fun ViewContainer<*, *>.SftpAudioViewer(
    mediaUrlProvider: () -> String?,
    fileNameProvider: () -> String
) {
    View {
        attr { flex(1f); backgroundColor(SftpColorTokens.bg); padding(16f, 16f, 16f, 16f); flexDirectionColumn() }
        // 文件名
        View {
            attr { marginBottom(16f); allCenter() }
            Text {
                attr {
                    text("🎵 " + fileNameProvider())
                    fontSize(16f)
                    fontWeightBold()
                    color(SftpColorTokens.textPrimary)
                }
            }
        }
        // 占位封面（音频无画面）
        View {
            attr {
                size(pagerData.pageViewWidth - 32f, 180f)
                backgroundColor(SftpColorTokens.cardBg)
                borderRadius(12f)
                allCenter()
                marginBottom(16f)
            }
            Text { attr { text("🎵"); fontSize(64f); color(SftpColorTokens.primary) } }
        }
        // 只负责渲染：播放地址由页面异步申请代理 token 后传入
        val url = mediaUrlProvider()
        Video {
            attr {
                if (!url.isNullOrEmpty()) {
                    src(url)
                    playControl(VideoPlayControl.PLAY)
                }
                resizeModeToContain()
            }
            event {
                // Phase 1: 接入 playStateDidChanged + playTimeDidChanged 落历史
            }
        }
        // 元信息
        View {
            attr { marginTop(16f); allCenter() }
            Text {
                attr {
                    text(I18n.t("sftp.viewer.audio_phase_hint"))
                    fontSize(11f)
                    color(SftpColorTokens.textSecondary)
                }
            }
        }
    }
}
