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
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.sftp.I18n
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.sftp.SftpMediaProxyModule
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.timer.clearTimeout
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
    /** 仅用于「显示」的播放进度：由 playTimeDidChanged 刷新 */
    private var currentPosition: Int by observable(0)
    /**
     * 仅用于「下发 seek」的目标位置。
     * 千万不要把它和 currentPosition 合成一个变量：那样每次进度回调都会改写
     * seekTo 属性，导致每秒一次真实 seek（VLC 每次都 flush + 重缓冲）＝播放卡顿。
     */
    private var seekTarget: Int by observable(-1)   // -1 = 未请求 seek（避免起播前下发 seekTo(0)）
    private var muted: Boolean by observable(false)
    /** 是否正在播放（驱动 playControl 与按钮图标） */
    private var isPlaying: Boolean by observable(true)
    /** 是否全屏（全屏时隐藏导航栏，原生侧旋转屏幕） */
    private var isFullscreen: Boolean by observable(false)
    /** Video 视图引用：用于调用原生能力（如全屏旋转） */
    private var videoViewRef: ViewRef<VideoView>? = null
    /** 是否正在拖动进度条 */
    private var draggingProgress: Boolean by observable(false)
    /** 拖动中的比例（0..1），拖动时以它显示，松手才真正 seek（避免拖一次发几十次 seek） */
    private var dragRatio: Float by observable(0f)

    /** 全屏时控制条是否可见（自动隐藏）。非全屏时控制条恒显示，不受此值影响。 */
    private var controlsVisible: Boolean by observable(true)

    /** 已缓冲位置（毫秒）。Web 端由 Video 的 customEvent 回传，用于进度条「已缓冲」段。 */
    private var bufferedPosition: Int by observable(0)

    /** 播放倍速（对齐 Plyr 的 settings/speed） */
    private var speed: Float by observable(1f)

    /** 设置菜单（倍速）是否展开；展开时不自动隐藏控制条 */
    private var showSettingsMenu: Boolean by observable(false)
    /** 自动隐藏定时器 id */
    private var hideControlsTimer: String? = null

    /**
     * 宿主（桌面 Web）会把鼠标移动转成页面事件，用来「动一下就显示控制条」。
     * 移动端没有鼠标移动，靠触摸/点击触发，逻辑一致。
     */
    private val hostEventObserver = object : IPagerEventObserver {
        override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
            when (pagerEvent) {
                EVENT_CONTROLS_ACTIVITY -> if (isFullscreen) showControls()
                EVENT_FULLSCREEN_CHANGED -> {
                    val fs = eventData.optBoolean("fullscreen", isFullscreen)
                    if (fs != isFullscreen) {
                        isFullscreen = fs
                        if (fs) showControls()
                    }
                }
                EVENT_PLAYER_KEY -> handlePlayerKey(eventData.optString("key"))
            }
        }
    }

    private var token: String? = null
    private var playUrl: String? by observable(null)
    private var playError: String? by observable(null)
    private var hasResumePromptShown: Boolean by observable(false)
    private var resumePosition: Long = 0L
    private var nextEpisodeCountdown: Int by observable(0)
    private var showCountdown: Boolean by observable(false)
    private var episodes: List<String> by observable(emptyList())  // 同目录下的视频列表（§19.3.5 自动下一集）
    private var currentIndex: Int by observable(0)
    private var firstFrameShown: Boolean by observable(false)
    /** 已落历史的整秒，避免同一秒重复写盘 */
    private var lastSavedSecond: Int = -1

    override fun created() {
        super.created()
        addPagerEventObserver(hostEventObserver)
        val params = pageData.params
        sessionId = params.optString("sessionId", "")
        connectionId = params.optString("connectionId", "")
        connectionLabel = params.optString("connectionLabel", "")
        remotePath = params.optString("remotePath", "")
        name = params.optString("name", "")
        size = params.optLong("size", 0L)
        loadEpisodes()
        startPlayback()
    }

    /**
     * 拉取同目录视频列表，供「上一集 / 下一集 / 选集」使用。
     * 之前 episodes 一直是空列表，这三个按钮点了没有任何反应。
     */
    private fun loadEpisodes() {
        val dir = remotePath.substringBeforeLast('/', "")
        if (dir.isEmpty()) return
        val sid = sessionId.ifEmpty { connectionId }
        sftpModule().list(sid, dir) { entries, _, _ ->
            val videos = entries
                .filter { !it.isDir && it.name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS }
                .map { it.path }
                .sorted()
            if (videos.isEmpty()) return@list
            episodes = videos
            currentIndex = videos.indexOf(remotePath).coerceAtLeast(0)
        }
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
            // 顶部安全区：Android 沉浸式 / 刘海屏下，页面自绘导航栏会被状态栏遮挡，
            // 且状态栏区域会吞掉点击（表现为「+ 新建」点不动）。这里整体下移状态栏高度。
            View {
                attr {
                    width(pagerData.pageViewWidth)
                    height(pagerData.pageViewHeight)
                    paddingTop(if (ctx.isFullscreen) 0f else pagerData.statusBarHeight)
                }

            attr { backgroundColor(Color.BLACK) }

            // 顶部导航栏（全屏时隐藏，把空间让给画面）
            vif({ !ctx.isFullscreen }) {
            View {
                attr {
                    size(pagerData.pageViewWidth, 56f)
                    flexDirectionRow()
                    alignItemsCenter()
                    padding(16f, 8f, 16f, 8f)
                    backgroundColor(Color(0xFF1A1A1A))
                }
                View {
                    attr { size(36f, 36f); allCenter(); accessibility(SftpAccessibility.BTN_BACK) }
                    event { click { ctx.closeSelf() } }
                    Text { attr { text("<"); fontSize(22f); color(Color.WHITE) } }
                }
                Text {
                    attr {
                        text(ctx.name)
                        fontSize(15f)
                        color(Color.WHITE)
                        flex(1f)
                        marginLeft(8f)
                        lines(1)                 // 单行，避免逐字换行
                        textOverFlowTail()       // 超出用省略号
                    }
                }
            }
            }

            // 视频容器：占据窗口剩余高度，画面按 contain 等比缩放（随窗口自适应）
            View {
                attr { flex(1f); width(pagerData.pageViewWidth); backgroundColor(Color.BLACK) }
                // 全屏时点击画面切换控制条显隐（移动端主要靠这个；桌面 Web 还会靠鼠标移动）
                event { click { ctx.toggleControls() } }
                // 必须用 vif：playUrl 是异步拿到的，写成 body 结构层的 `let`/`if`
                // 时首次求值为 null，Video 视图不会被创建，之后也不会重建（=黑屏）。
                vif({ ctx.playUrl != null }) {
                    Video {
                        ref { ctx.videoViewRef = it }
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
                            muted(ctx.muted)
                            rate(ctx.speed)
                            // 只下发「显式 seek 目标」，绝不下发播放进度，
                            // 否则每秒的进度回调都会变成一次真实 seek（卡顿根因）
                            seekTo(ctx.seekTarget)
                        }
                        event {
                            firstFrameDidDisplay { ctx.firstFrameShown = true }
                            playStateDidChanged { state, _ -> ctx.onPlayStateChanged(state) }
                            playTimeDidChanged { cur, total -> ctx.onPlayTimeChanged(cur, total) }
                            // 引擎回传的扩展数据（Web：已缓冲进度）
                            customEvent { data ->
                                val obj = data as? JSONObject ?: return@customEvent
                                if (obj.optString("event") == "buffered") {
                                    val b = obj.optInt("bufferedTime")
                                    if (b > ctx.bufferedPosition) ctx.bufferedPosition = b
                                }
                            }
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
                // 中央大播放键：暂停时显示（对齐 Plyr 的 play-large）
                vif({ !ctx.isPlaying && ctx.playUrl != null }) {
                    View {
                        attr {
                            positionAbsolute()
                            left(0f); right(0f); top(0f); bottom(0f)
                            allCenter()
                        }
                        event { click { ctx.togglePlay() } }
                        View {
                            attr {
                                size(64f, 64f)
                                borderRadius(32f)
                                allCenter()
                                backgroundColor(Color(0x88000000))
                            }
                            Text { attr { text("▶"); fontSize(26f); color(Color.WHITE) } }
                        }
                    }
                }
            }

            // 控制面板：始终覆盖在视频底部（对齐 Plyr 的控件层），全屏时自动隐藏。
            vif({ !ctx.isFullscreen || ctx.controlsVisible }) {
            View {
                attr {
                    width(pagerData.pageViewWidth)
                    positionAbsolute()
                    bottom(0f)
                    padding(12f, 8f, 12f, 10f)
                    flexDirectionColumn()
                    backgroundColor(Color(0xCC101010))
                }
                // ── 进度条：已缓冲段 + 已播段 + 滑块 + 拖动时间预览 ──
                View {
                    attr {
                        width(pagerData.pageViewWidth - 24f)
                        height(28f)          // 触摸区比视觉高度大，便于拖拽
                        justifyContentCenter()
                        backgroundColor(Color(0x00000000))
                        touchEnable(true)    // 显式开启触摸，保证 pan 能收到
                    }
                    // 轨道（子元素用绝对定位叠放；规格对齐 Plyr：track 5 / thumb 13）
                    View {
                        attr {
                            width(pagerData.pageViewWidth - 24f)
                            height(5f)
                            borderRadius(3f)
                            backgroundColor(Color(0x55FFFFFF))
                        }
                        // 已缓冲段
                        View {
                            attr {
                                positionAbsolute()
                                left(0f); top(0f)
                                width((pagerData.pageViewWidth - 24f) * ctx.bufferedRatio())
                                height(5f)
                                borderRadius(3f)
                                backgroundColor(Color(0x99FFFFFF))
                            }
                        }
                        // 已播段
                        View {
                            attr {
                                positionAbsolute()
                                left(0f); top(0f)
                                width((pagerData.pageViewWidth - 24f) * ctx.displayRatio())
                                height(5f)
                                borderRadius(3f)
                                backgroundColor(Color(0xFF3D7EFF))
                            }
                        }
                        // 滑块：拖动时变大并加外圈（Plyr thumb-active-shadow）
                        View {
                            attr {
                                positionAbsolute()
                                left(((pagerData.pageViewWidth - 24f) * ctx.displayRatio()) -
                                    (if (ctx.draggingProgress) 10f else 7f))
                                top(if (ctx.draggingProgress) -8f else -4f)
                                size(if (ctx.draggingProgress) 20f else 14f,
                                      if (ctx.draggingProgress) 20f else 14f)
                                borderRadius(if (ctx.draggingProgress) 10f else 7f)
                                backgroundColor(if (ctx.draggingProgress) Color(0x553D7EFF) else Color(0x00000000))
                                allCenter()
                            }
                            View {
                                attr {
                                    size(13f, 13f)
                                    borderRadius(7f)
                                    backgroundColor(Color(0xFFFFFFFF))
                                }
                            }
                        }
                    }
                    // 拖动时在滑块上方显示目标时间
                    if (ctx.draggingProgress) {
                        View {
                            attr {
                                positionAbsolute()
                                left(((pagerData.pageViewWidth - 24f) * ctx.dragRatio) - 26f)
                                top(-26f)
                                padding(6f, 2f, 6f, 2f)
                                borderRadius(4f)
                                backgroundColor(Color(0xCC000000))
                            }
                            Text {
                                attr {
                                    text(formatTime((ctx.duration * ctx.dragRatio).toLong()))
                                    fontSize(11f)
                                    color(Color.WHITE)
                                }
                            }
                        }
                    }
                    event {
                        // Kuikly 的拖拽手势是 `pan`（不是 touchDown/Move/Up：
                        // 实测 macOS 上 touch* 不回调，pan 才带 start/move/end）
                        pan { p ->
                            when (p.state) {
                                "start" -> ctx.beginDragProgress(p.x)
                                "move" -> ctx.updateDragProgress(p.x)
                                "end" -> {
                                    ctx.updateDragProgress(p.x)
                                    ctx.endDragProgress()
                                }
                            }
                        }
                    }
                }
                // 按钮行：播放控制 + 时间 + 倍速/静音/选集/全屏
                View {
                    attr { flexDirectionRow(); alignItemsCenter(); marginTop(4f) }
                    // 播放 / 暂停
                    View {
                        attr {
                            size(44f, 44f); allCenter(); borderRadius(22f)
                            backgroundColor(Color(0xFF2A2A2A))
                            accessibility(if (ctx.isPlaying) SftpAccessibility.BTN_PAUSE else SftpAccessibility.BTN_PLAY)
                        }
                        event { click { ctx.togglePlay() } }
                        Text {
                            attr {
                                text(if (ctx.isPlaying) "❚❚" else "▶")
                                fontSize(if (ctx.isPlaying) 15f else 18f)
                                color(Color.WHITE)
                            }
                        }
                    }
                    // 快退 10s
                    View {
                        attr { size(40f, 40f); allCenter(); marginLeft(6f); accessibility(SftpAccessibility.BTN_SEEK_BACKWARD) }
                        event { click { ctx.seekBy(-10_000) } }
                        Text { attr { text("◀◀"); fontSize(12f); color(Color.WHITE) } }
                    }
                    // 快进 10s
                    View {
                        attr { size(40f, 40f); allCenter(); marginLeft(6f); accessibility(SftpAccessibility.BTN_SEEK_FORWARD) }
                        event { click { ctx.seekBy(10_000) } }
                        Text { attr { text("▶▶"); fontSize(12f); color(Color.WHITE) } }
                    }
                    // 时间
                    Text {
                        attr {
                            text(formatTime(ctx.currentPosition.toLong()) + " / " + formatTime(ctx.duration.toLong()))
                            fontSize(12f)
                            color(Color(0xFFBBBBBB))
                            marginLeft(10f)
                        }
                    }
                    // 占位：把右侧按钮推到最右
                    View { attr { flex(1f) } }
                    // 设置（倍速菜单，对齐 Plyr 的 settings）
                    View {
                        attr { size(44f, 40f); allCenter() }
                        event { click { ctx.showSettingsMenu = !ctx.showSettingsMenu; ctx.showControls() } }
                        Text {
                            attr {
                                text(if (ctx.speed == 1f) "1.0×" else "${ctx.speed}×")
                                fontSize(12f)
                                color(Color.WHITE)
                            }
                        }
                    }
                    // 静音
                    View {
                        attr { size(40f, 40f); allCenter() }
                        event { click { ctx.muted = !ctx.muted } }
                        Text { attr { text(if (ctx.muted) "🔇" else "🔊"); fontSize(14f); color(Color.WHITE) } }
                    }
                    // 选集
                    View {
                        attr { size(40f, 40f); allCenter(); accessibility(SftpAccessibility.BTN_EPISODE_LIST) }
                        event { click { ctx.showEpisodeDrawer = !ctx.showEpisodeDrawer } }
                        Text { attr { text("☰"); fontSize(15f); color(Color.WHITE) } }
                    }
                    // 全屏
                    View {
                        attr { size(40f, 40f); allCenter(); accessibility(SftpAccessibility.BTN_FULLSCREEN) }
                        event { click { ctx.toggleFullscreen() } }
                        Text { attr { text(if (ctx.isFullscreen) "⤡" else "⛶"); fontSize(16f); color(Color.WHITE) } }
                    }
                }
            }
            }

            // 续播提示对话框（必须用 vif：body 结构层的 if 只在首帧求值，不会响应式重建）
            vif({ ctx.hasResumePromptShown }) {
                SftpResumePromptDialog(
                    resumeMs = ctx.resumePosition,
                    onContinue = {
                        ctx.hasResumePromptShown = false
                        ctx.applySeek(ctx.resumePosition.toInt())   // 真正跳到上次位置
                    },
                    onRestart = {
                        ctx.hasResumePromptShown = false
                        ctx.applySeek(0)                            // 从头播
                    }
                )
            }

            // 自动下一集倒计时
            vif({ ctx.showCountdown }) {
                SftpNextEpisodeCountdownDialog(
                    seconds = ctx.nextEpisodeCountdown,
                    onCancel = { ctx.showCountdown = false },
                    onPlayNow = { ctx.showCountdown = false; ctx.playNextEpisode() }
                )
            }

            // 播放设置菜单（倍速，对齐 Plyr 的 settings）
            vif({ ctx.showSettingsMenu }) {
                SftpPlayerSettingsMenu(ctx.speed) { picked ->
                    ctx.speed = picked
                    ctx.showSettingsMenu = false
                }
            }

            // 选集抽屉
            vif({ ctx.showEpisodeDrawer && ctx.episodes.isNotEmpty() }) {
                SftpEpisodeDrawer(ctx.episodes, ctx.currentIndex) { index ->
                    ctx.showEpisodeDrawer = false
                    ctx.switchToEpisode(index)
                }
            }
                    }
}
    }

    private var showEpisodeDrawer: Boolean by observable(false)

    private fun onPlayStateChanged(state: PlayState) {
        // 注意：isPlaying 表示「用户意图」，只由播放键切换，不能跟随播放器状态自动改写。
        // 否则 seek 时为了保持 demuxer 活跃会短暂起播，状态回调把 isPlaying 改成 true，
        // 暂停中拖动/快进快退后按钮就会显示成「暂停」。
        // 仅当真的播完（或出错）时才复位意图。
        if (state == PlayState.PLAY_END || state == PlayState.ERROR) {
            isPlaying = false
        }
        if (state == PlayState.PLAY_END) {
            // §19.3.5 自动下一集：3s 倒计时（此前只赋值不递减，弹层永远停在 3s）
            sftpPlaybackHistoryModule().markCompleted(connectionId, remotePath) { _, _ -> }
            if (episodes.isEmpty() || currentIndex >= episodes.size - 1) return
            nextEpisodeCountdown = 3
            showCountdown = true
            tickCountdown()
        }
    }

    /** 进度比例 0..1，供进度条使用（在 attr 内读取才会被依赖收集） */
    private fun progressRatio(): Float {
        if (duration <= 0) return 0f
        return (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }

    /** 进度条显示用比例：拖动中显示拖动位置，否则显示真实进度 */
    private fun displayRatio(): Float = if (draggingProgress) dragRatio else progressRatio()

    private fun ratioFromX(x: Float): Float {
        val w = pagerData.pageViewWidth - 24f   // 与进度条轨道宽度一致（控制面板左右各留 12）
        if (w <= 0f) return 0f
        return (x / w).coerceIn(0f, 1f)
    }

    private fun beginDragProgress(x: Float) {
        draggingProgress = true
        dragRatio = ratioFromX(x)
        // 重置自动隐藏计时，避免拖动过程中面板被隐藏而中断手势
        showControls()
    }

    private fun updateDragProgress(x: Float) {
        dragRatio = ratioFromX(x)
    }

    /** 松手才落到真实进度，触发一次 seek */
    private fun endDragProgress() {
        if (!draggingProgress) return
        draggingProgress = false
        if (duration > 0) {
            val target = (duration * dragRatio).toInt().coerceIn(0, duration)
            applySeek(target)
        }
    }

    /** 显式跳转：更新显示位置 + 下发 seek（progress 回调不会走这里） */
    private fun applySeek(targetMs: Int) {
        val clamped = if (duration > 0) targetMs.coerceIn(0, duration) else targetMs.coerceAtLeast(0)
        currentPosition = clamped
        seekTarget = clamped
    }

    /** 返回上一页（离开前落一次播放历史并释放代理 token） */
    private fun closeSelf() {
        removePagerEventObserver(hostEventObserver)
        hideControlsTimer?.let { clearTimeout(it) }
        hideControlsTimer = null
        if (duration > 0 && currentPosition > 0) {
            savePlaybackHistory(currentPosition.toLong(), duration.toLong(), false)
        }
        token?.let { tk ->
            token = null
            sftpMediaProxyModule().unregisterToken(tk)
        }
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
    }

    /** 切换全屏：页面隐藏导航栏，原生侧负责屏幕旋转 */
    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        videoViewRef?.view?.setFullscreen(isFullscreen)
        if (isFullscreen) showControls() else controlsVisible = true
    }

    /** 显示控制条并重新计时自动隐藏（全屏时用） */
    private fun showControls() {
        controlsVisible = true
        scheduleHideControls()
    }

    /** 3 秒无操作后隐藏控制条（仅全屏且正在播放时） */
    private fun scheduleHideControls() {
        hideControlsTimer?.let { clearTimeout(it) }
        hideControlsTimer = setTimeout(2000) {
            // 正在拖动进度 / 打开设置菜单时绝不隐藏：隐藏会移除手势元素，拖动会被中断
            if (isFullscreen && isPlaying && !draggingProgress && !showSettingsMenu) {
                controlsVisible = false
            }
        }
    }

    /** 键盘快捷键（桌面 Web）：对齐 Plyr 的常用键位 */
    private fun handlePlayerKey(key: String) {
        when (key.lowercase()) {
            " ", "k" -> togglePlay()
            "arrowleft" -> seekBy(-10_000)
            "arrowright" -> seekBy(10_000)
            "m" -> muted = !muted
            "f" -> toggleFullscreen()
        }
        showControls()
    }

    /** 点击画面：全屏时切换控制条显隐 */
    private fun toggleControls() {
        if (!isFullscreen) return
        if (controlsVisible) {
            hideControlsTimer?.let { clearTimeout(it) }
            hideControlsTimer = null
            controlsVisible = false
        } else {
            showControls()
        }
    }

    /** 已缓冲比例（0..1） */
    private fun bufferedRatio(): Float =
        if (duration <= 0) 0f else (bufferedPosition.toFloat() / duration).coerceIn(0f, 1f)

    /** 循环切换倍速（对齐 Plyr 的 settings/speed） */
    private fun cycleSpeed() {
        val options = floatArrayOf(0.5f, 1f, 1.25f, 1.5f, 2f)
        var idx = -1
        for (i in options.indices) {
            if (kotlin.math.abs(options[i] - speed) < 0.01f) { idx = i; break }
        }
        speed = options[if (idx < 0) 1 else (idx + 1) % options.size]
        showControls()
    }

    private fun togglePlay() {
        isPlaying = !isPlaying   // 驱动 playControl(PLAY/PAUSE)
        showControls()
    }

    private fun seekBy(deltaMs: Int) {
        if (duration <= 0) return
        applySeek(currentPosition + deltaMs)
    }

    private fun onPlayTimeChanged(cur: Int, total: Int) {
        // 拖动进度条期间不要被播放回调覆盖显示值
        if (!draggingProgress) currentPosition = cur
        if (total > 0) duration = total
        // §19.3.3 每 5s 落一次历史。
        // 注意：cur/total 单位是**毫秒**——之前写成 `cur % 5 == 0` 是按秒的直觉，
        // 毫秒下几乎每个回调都命中，导致每帧写一次磁盘（卡顿 + 日志刷屏）。
        if (duration <= 0) return
        val curSec = cur / 1000
        if (curSec > 0 && curSec % 5 == 0 && curSec != lastSavedSecond) {
            lastSavedSecond = curSec
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

    /** 每秒递减；到 0 自动切下一集 */
    private fun tickCountdown() {
        setTimeout(1000) {
            if (!showCountdown) return@setTimeout
            nextEpisodeCountdown -= 1
            if (nextEpisodeCountdown <= 0) {
                showCountdown = false
                playNextEpisode()
            } else {
                tickCountdown()
            }
        }
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

        /** 宿主（桌面 Web）鼠标/触摸活动：用于显示控制条 */
        private const val EVENT_CONTROLS_ACTIVITY = "sftp_controls_activity"

        /** 全屏状态变化（含用户按 ESC 退出）：宿主回传，保持页面状态同步 */
        private const val EVENT_FULLSCREEN_CHANGED = "sftp_fullscreen_changed"

        /** 键盘快捷键（桌面 Web）：宿主 keydown 转页面事件 */
        private const val EVENT_PLAYER_KEY = "sftp_player_key"

        /** 视为「可连播」的扩展名（§19.3.5 自动下一集） */
        private val VIDEO_EXTENSIONS =
            setOf("mp4", "m4v", "mov", "mkv", "avi", "webm", "ts", "flv", "wmv")
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

/**
 * 播放设置菜单（对齐 Plyr 的 settings）。
 * 当前只提供倍速；字幕 / 清晰度等需引擎能力，后续可在此扩展。
 */
internal fun ViewContainer<*, *>.SftpPlayerSettingsMenu(
    currentSpeed: Float,
    onPick: (Float) -> Unit
) {
    val options = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)
    View {
        attr {
            positionAbsolute()
            right(12f)
            bottom(60f)
            width(132f)
            padding(6f, 6f, 6f, 6f)
            borderRadius(10f)
            flexDirectionColumn()
            backgroundColor(Color(0xF0222222))
        }
        options.forEach { opt ->
            View {
                attr {
                    width(120f)
                    height(36f)
                    allCenter()
                    borderRadius(6f)
                    backgroundColor(if (opt == currentSpeed) Color(0xFF3D7EFF) else Color(0x00000000))
                }
                event { click { onPick(opt) } }
                Text {
                    attr {
                        text("${opt}×")
                        fontSize(13f)
                        color(Color.WHITE)
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
