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
package com.tencent.kuikly.demo.pages.sftp

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.sftp.I18n
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.sftp.SftpMediaProxyModule
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.module.sftp.SftpMediaUrlBuilder
import com.tencent.kuikly.core.module.sftp.SftpPlaybackRecord
import com.tencent.kuikly.core.datetime.DateTime
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.views.PlayState
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.Video
import com.tencent.kuikly.core.views.VideoPlayControl
import com.tencent.kuikly.core.views.VideoView
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.demo.pages.sftp.theme.SftpAccessibility
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * SFTP 播放页（§19 / §21.6.3）
 *
 * 流程：
 * 1. 注册代理 token → 拿本地 URL（§21.3）
 * 2. 取播放历史 [SftpPlaybackHistoryModule.get]；position > 0 → 弹续播对话框
 * 3. [VideoView] 加载本地 URL，首帧显示超时 5s 提示
 * 4. 播放过程中每 5s 落一次历史（§19.3.3）
 * 5. 播放完成（[PlayState.PLAY_END]）→ markCompleted，自动播下一集（§19.3.5）
 * 6. 离开页 unregister token
 */
@Page(SftpPlayerPage.PAGE_NAME)
internal class SftpPlayerPage : SftpBasePager() {

    private var sessionId: String = ""
    private var connectionId: String = ""
    private var connectionLabel: String = ""
    private var remotePath: String = ""
    private var name: String = ""
    private var size: Long = 0L
    // 播放进度与状态都要可观察：控制条依赖它们实时刷新
    private var duration: Int by observable(0)
    private var currentPosition: Int by observable(0)
    /** 是否正在播放（驱动 playControl 与按钮图标） */
    private var isPlaying: Boolean by observable(true)

    private var token: String? = null
    private var playUrl: String? by observable(null)
    private var playError: String? by observable(null)
    private var hasResumePromptShown: Boolean = false
    private var resumePosition: Long = 0L
    private var nextEpisodeCountdown: Int = 0
    private var showCountdown: Boolean = false
    private var episodes: List<String> = emptyList()  // 同目录下的视频列表（§19.3.5 自动下一集）
    private var currentIndex: Int = 0
    private var firstFrameShown: Boolean by observable(false)

    override fun created() {
        super.created()
        val params = pageData.params
        sessionId = params.optString("sessionId", "")
        connectionId = params.optString("connectionId", "")
        connectionLabel = params.optString("connectionLabel", "")
        remotePath = params.optString("remotePath", "")
        name = params.optString("name", "")
        size = params.optLong("size", 0L)
        startPlayback()
    }

    private fun startPlayback() {
        // 1. 启动本地代理 → 注册 token → 拼出 http://127.0.0.1:<port>/<token>/<name>
        //    代理会把播放器的 HTTP Range 请求转成 SFTP lseek+read（§5.2），实现边下边播。
        val proxy = sftpMediaProxyModule()
        val sftpSessionId = sessionId.ifEmpty { connectionId }  // 降级：BrowserPage 旧链路可能未传 sessionId
        proxy.startOrGetPort { port ->
            proxy.registerToken(sftpSessionId, remotePath, size) { tk ->
                if (port <= 0 || tk.isEmpty()) {
                    playError = if (port <= 0) I18n.t("sftp.error.proxy_start_failed")
                                else I18n.t("sftp.error.proxy_read_failed")
                    return@registerToken
                }
                token = tk
                playUrl = SftpMediaUrlBuilder.buildPlayUrl(port, tk, name)
            }
        }

        // 2. 取播放历史
        sftpPlaybackHistoryModule().get(connectionId, remotePath) { record, _ ->
            record?.let {
                resumePosition = it.position
                duration = it.duration.toInt()
                hasResumePromptShown = it.position > 10_000L  // > 10s 才提示
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { backgroundColor(Color.BLACK) }

            // 视频容器：占据窗口剩余高度，画面按 contain 等比缩放（随窗口自适应）
            View {
                attr { flex(1f); width(pagerData.pageViewWidth); backgroundColor(Color.BLACK) }
                // 必须用 vif：playUrl 是异步拿到的，写成 body 结构层的 `let`/`if`
                // 时首次求值为 null，Video 视图不会被创建，之后也不会重建（=黑屏）。
                vif({ ctx.playUrl != null }) {
                    Video {
                        attr {
                            // 必须给 Video 尺寸：只给外层容器尺寸时 Video 高度为 0，
                            // VLC 渲染视图高度也是 0（黑屏）。这里跟随容器填满。
                            flex(1f)
                            width(pagerData.pageViewWidth)
                            src(ctx.playUrl ?: "")
                            resizeModeToContain()
                            // 关键：不设 playControl 时 VLC 只创建播放器不会起播，
                            // 也就不会向本地代理发起 Range 请求（表现为黑屏且代理无请求）
                            playControl(if (ctx.isPlaying) VideoPlayControl.PLAY else VideoPlayControl.PAUSE)
                            // seekTo 以属性下发；原生侧对相同值去重，不会每帧重复 seek
                            seekTo(ctx.currentPosition)
                        }
                        event {
                            firstFrameDidDisplay { ctx.firstFrameShown = true }
                            playStateDidChanged { state, _ -> ctx.onPlayStateChanged(state) }
                            playTimeDidChanged { cur, total -> ctx.onPlayTimeChanged(cur, total) }
                        }
                    }
                }
                // 代理/打开失败时明确提示，而不是停在“加载中”
                vif({ ctx.playError != null }) {
                    View {
                        attr { allCenter(); size(pagerData.pageViewWidth, 80f) }
                        Text {
                            attr {
                                text(ctx.playError ?: "")
                                fontSize(14f)
                                color(Color(0xFFFF6B6B))
                            }
                        }
                    }
                }
                // 首帧未显示 + loading 提示（Phase 0.3 简化）
                vif({ !ctx.firstFrameShown && ctx.playUrl != null }) {
                    View {
                        attr { allCenter(); size(pagerData.pageViewWidth, 80f) }
                        Text {
                            attr {
                                text(I18n.t("sftp.ui.loading"))
                                fontSize(14f)
                                color(Color.WHITE)
                            }
                        }
                    }
                }
            }

            // 控制条：进度 + 时间 + 播放/暂停 + 快退快进 + 选集
            View {
                attr {
                    width(pagerData.pageViewWidth)
                    padding(16f, 10f, 16f, 14f)
                    flexDirectionColumn()
                    backgroundColor(Color(0xFF101010))
                }
                // 进度条
                View {
                    attr {
                        width(pagerData.pageViewWidth - 32f)
                        height(4f)
                        borderRadius(2f)
                        backgroundColor(Color(0xFF3A3A3A))
                    }
                    View {
                        attr {
                            width((pagerData.pageViewWidth - 32f) * ctx.progressRatio())
                            height(4f)
                            borderRadius(2f)
                            backgroundColor(Color(0xFF3D7EFF))
                        }
                    }
                }
                // 时间
                Text {
                    attr {
                        text(formatTime(ctx.currentPosition.toLong()) + " / " + formatTime(ctx.duration.toLong()))
                        fontSize(12f)
                        color(Color(0xFFBBBBBB))
                        marginTop(6f)
                    }
                }
                // 按钮行
                View {
                    attr { flexDirectionRow(); alignItemsCenter(); marginTop(8f) }
                    // 快退 10s
                    View {
                        attr { size(40f, 40f); allCenter(); accessibility(SftpAccessibility.BTN_SEEK_BACKWARD) }
                        event { click { ctx.seekBy(-10_000) } }
                        Text { attr { text("⏪"); fontSize(16f); color(Color.WHITE) } }
                    }
                    // 播放 / 暂停
                    View {
                        attr {
                            size(44f, 44f); allCenter(); borderRadius(22f)
                            backgroundColor(Color(0xFF2A2A2A))
                            marginLeft(8f)
                            accessibility(if (ctx.isPlaying) SftpAccessibility.BTN_PAUSE else SftpAccessibility.BTN_PLAY)
                        }
                        event { click { ctx.togglePlay() } }
                        Text {
                            attr {
                                text(if (ctx.isPlaying) "⏸" else "▶")
                                fontSize(18f)
                                color(Color.WHITE)
                            }
                        }
                    }
                    // 快进 10s
                    View {
                        attr { size(40f, 40f); allCenter(); marginLeft(8f); accessibility(SftpAccessibility.BTN_SEEK_FORWARD) }
                        event { click { ctx.seekBy(10_000) } }
                        Text { attr { text("⏩"); fontSize(16f); color(Color.WHITE) } }
                    }
                    // 上一集 / 选集 / 下一集
                    View {
                        attr { size(40f, 40f); allCenter(); marginLeft(16f); accessibility(SftpAccessibility.BTN_PREV_EPISODE) }
                        event { click { ctx.playPrevEpisode() } }
                        Text { attr { text("◀"); fontSize(15f); color(Color.WHITE) } }
                    }
                    View {
                        attr { size(40f, 40f); allCenter(); accessibility(SftpAccessibility.BTN_EPISODE_LIST) }
                        event { click { ctx.showEpisodeDrawer = !ctx.showEpisodeDrawer } }
                        Text { attr { text("☰"); fontSize(15f); color(Color.WHITE) } }
                    }
                    View {
                        attr { size(40f, 40f); allCenter(); accessibility(SftpAccessibility.BTN_NEXT_EPISODE) }
                        event { click { ctx.playNextEpisode() } }
                        Text { attr { text("▶"); fontSize(15f); color(Color.WHITE) } }
                    }
                    // 文件名
                    Text {
                        attr {
                            text(ctx.name)
                            fontSize(12f)
                            color(Color(0xFF999999))
                            flex(1f)
                            marginLeft(8f)
                        }
                    }
                }
            }

            // 续播提示对话框
            if (ctx.hasResumePromptShown) {
                SftpResumePromptDialog(
                    resumeMs = ctx.resumePosition,
                    onContinue = {
                        ctx.hasResumePromptShown = false
                        // TODO Phase 0.3: 调 videoView.seekTo(resumePosition)
                    },
                    onRestart = {
                        ctx.hasResumePromptShown = false
                    }
                )
            }

            // 自动下一集倒计时
            if (ctx.showCountdown) {
                SftpNextEpisodeCountdownDialog(
                    seconds = ctx.nextEpisodeCountdown,
                    onCancel = { ctx.showCountdown = false },
                    onPlayNow = { ctx.showCountdown = false; ctx.playNextEpisode() }
                )
            }

            // 选集抽屉
            if (ctx.showEpisodeDrawer && ctx.episodes.isNotEmpty()) {
                SftpEpisodeDrawer(ctx.episodes, ctx.currentIndex) { index ->
                    ctx.showEpisodeDrawer = false
                    ctx.switchToEpisode(index)
                }
            }
        }
    }

    private var showEpisodeDrawer: Boolean = false

    private fun onPlayStateChanged(state: PlayState) {
        when (state) {
            PlayState.PLAYING -> isPlaying = true
            PlayState.PAUSED -> isPlaying = false
            else -> {}
        }
        if (state == PlayState.PLAY_END) {
            // §19.3.5 自动下一集：3s 倒计时
            sftpPlaybackHistoryModule().markCompleted(connectionId, remotePath) { _, _ -> }
            nextEpisodeCountdown = 3
            showCountdown = true
        }
    }

    /** 进度比例 0..1，供进度条使用（在 attr 内读取才会被依赖收集） */
    private fun progressRatio(): Float {
        if (duration <= 0) return 0f
        return (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }

    private fun togglePlay() {
        isPlaying = !isPlaying   // 驱动 playControl(PLAY/PAUSE)
    }

    private fun seekBy(deltaMs: Int) {
        if (duration <= 0) return
        currentPosition = (currentPosition + deltaMs).coerceIn(0, duration)
    }

    private fun onPlayTimeChanged(cur: Int, total: Int) {
        currentPosition = cur
        if (total > 0) duration = total
        // §19.3.3 每 5s 落一次历史
        if (cur > 0 && cur % 5 == 0 && duration > 0) {
            savePlaybackHistory(cur.toLong(), duration.toLong(), false)
        }
    }

    private fun savePlaybackHistory(position: Long, duration: Long, completed: Boolean) {
        val record = SftpPlaybackRecord(
            id = SftpPlaybackRecord.buildId(connectionId, remotePath),
            connectionId = connectionId,
            connectionLabel = connectionLabel,
            remotePath = remotePath,
            name = name,
            duration = duration,
            position = position,
            completed = completed,
            lastPlayedAt = DateTime.currentTimestamp(),
            size = size
        )
        sftpPlaybackHistoryModule().upsert(record) { _, _ -> }
    }

    private fun playNextEpisode() {
        if (episodes.isEmpty()) return
        val next = (currentIndex + 1).coerceAtMost(episodes.size - 1)
        if (next == currentIndex) return
        switchToEpisode(next)
    }

    private fun playPrevEpisode() {
        if (episodes.isEmpty()) return
        val prev = (currentIndex - 1).coerceAtLeast(0)
        if (prev == currentIndex) return
        switchToEpisode(prev)
    }

    private fun switchToEpisode(index: Int) {
        currentIndex = index
        remotePath = episodes[index]
        name = remotePath.substringAfterLast('/')
        firstFrameShown = false
        startPlayback()
    }

    override fun pageWillDestroy() {
        super.pageWillDestroy()
        // §19.3.4 离开页落最后历史
        if (duration > 0 && currentPosition > 0) {
            savePlaybackHistory(currentPosition.toLong(), duration.toLong(), false)
        }
        // 取消 token（释放远端 fileHandle）
        token?.let { sftpMediaProxyModule().unregisterToken(it) }
    }

    companion object {
        const val PAGE_NAME = "SftpPlayerPage"
    }
}

/** 续播提示对话框 */
internal fun ViewContainer<*, *>.SftpResumePromptDialog(
    resumeMs: Long,
    onContinue: () -> Unit,
    onRestart: () -> Unit
) {
    View {
        attr {
            size(pagerData.pageViewWidth, pagerData.pageViewHeight)
            backgroundColor(Color(0x99000000.toInt()))
            allCenter()
        }
        View {
            attr {
                width(280f)
                padding(24f, 20f, 24f, 20f)
                backgroundColor(SftpColorTokens.cardBg)
                borderRadius(12f)
                flexDirectionColumn()
            }
            Text {
                attr {
                    text(I18n.t("sftp.player.resume_title"))
                    fontSize(16f)
                    fontWeightBold()
                    color(SftpColorTokens.textPrimary)
                    marginBottom(12f)
                }
            }
            Text {
                attr {
                    text("${I18n.t("sftp.player.resume_prompt")} ${formatTime(resumeMs)}")
                    fontSize(14f)
                    color(SftpColorTokens.textSecondary)
                    marginBottom(20f)
                }
            }
            View {
                attr { flexDirectionRow(); allCenter() }
                View {
                    attr {
                        flex(1f); height(40f); allCenter()
                        backgroundColor(SftpColorTokens.divider); borderRadius(8f)
                        marginRight(8f)
                    }
                    event { click { onRestart() } }
                    Text { attr { text(I18n.t("sftp.player.restart")); fontSize(14f); color(SftpColorTokens.textPrimary) } }
                }
                View {
                    attr {
                        flex(1f); height(40f); allCenter()
                        backgroundColor(SftpColorTokens.primary); borderRadius(8f)
                    }
                    event { click { onContinue() } }
                    Text { attr { text(I18n.t("sftp.player.continue_play")); fontSize(14f); color(Color.WHITE) } }
                }
            }
        }
    }
}

/** 自动下一集倒计时对话框（§19.3.5） */
internal fun ViewContainer<*, *>.SftpNextEpisodeCountdownDialog(
    seconds: Int,
    onCancel: () -> Unit,
    onPlayNow: () -> Unit
) {
    View {
        attr {
            size(pagerData.pageViewWidth, 80f)
            backgroundColor(Color(0x99000000.toInt()))
            flexDirectionRow(); alignItemsCenter()
            padding(16f, 12f, 16f, 12f)
        }
        Text {
            attr {
                text("${I18n.t("sftp.player.next_episode_countdown")} ${seconds}s")
                fontSize(14f)
                color(Color.WHITE)
                flex(1f)
            }
        }
        View {
            attr { height(32f); padding(8f, 12f, 8f, 12f); allCenter(); backgroundColor(SftpColorTokens.divider); borderRadius(8f); marginRight(8f) }
            event { click { onCancel() } }
            Text { attr { text(I18n.t("sftp.ui.cancel")); fontSize(13f); color(SftpColorTokens.textPrimary) } }
        }
        View {
            attr { height(32f); padding(8f, 12f, 8f, 12f); allCenter(); backgroundColor(SftpColorTokens.primary); borderRadius(8f) }
            event { click { onPlayNow() } }
            Text { attr { text(I18n.t("sftp.player.play_now")); fontSize(13f); color(Color.WHITE) } }
        }
    }
}

/** 选集抽屉（§19.3.6） */
internal fun ViewContainer<*, *>.SftpEpisodeDrawer(
    episodes: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit
) {
    View {
        attr {
            size(pagerData.pageViewWidth, pagerData.pageViewHeight)
            backgroundColor(Color(0x99000000.toInt()))
            alignItemsFlexEnd()
        }
        View {
            attr {
                width(pagerData.pageViewWidth)
                height(pagerData.pageViewHeight * 0.6f)
                backgroundColor(SftpColorTokens.cardBg)
                padding(16f, 16f, 16f, 16f)
                flexDirectionColumn()
            }
            Text {
                attr {
                    text(I18n.t("sftp.player.episodes_title"))
                    fontSize(16f)
                    fontWeightBold()
                    color(SftpColorTokens.textPrimary)
                    marginBottom(12f)
                }
            }
            episodes.forEachIndexed { index, path ->
                View {
                    attr {
                        width(pagerData.pageViewWidth - 32f)
                        padding(12f, 10f, 12f, 10f)
                        backgroundColor(if (index == currentIndex) SftpColorTokens.primary else Color.TRANSPARENT)
                        borderRadius(8f)
                        marginBottom(4f)
                    }
                    event { click { onSelect(index) } }
                    Text {
                        attr {
                            text(path.substringAfterLast('/'))
                            fontSize(14f)
                            color(if (index == currentIndex) Color.WHITE else SftpColorTokens.textPrimary)
                        }
                    }
                }
            }
        }
    }
}

/** 毫秒 → mm:ss（commonMain 无 String.format，手动补零） */
internal fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    val mStr = if (m < 10) "0$m" else "$m"
    val sStr = if (s < 10) "0$s" else "$s"
    return "$mStr:$sStr"
}
