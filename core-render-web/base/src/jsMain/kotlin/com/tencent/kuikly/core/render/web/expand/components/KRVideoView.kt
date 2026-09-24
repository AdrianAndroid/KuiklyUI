package com.tencent.kuikly.core.render.web.expand.components

import com.tencent.kuikly.core.render.web.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.web.ktx.KuiklyRenderCallback
import com.tencent.kuikly.core.render.web.ktx.kuiklyDocument
import com.tencent.kuikly.core.render.web.runtime.dom.element.ElementType
import com.tencent.kuikly.core.render.web.utils.Log
import org.w3c.dom.HTMLVideoElement

/**
 * Video playback operations
 */
enum class KRVideoViewPlayControl {
    KRVideoViewPlayControlNone,

    // Control pre-play video
    KRVideoViewPlayControlPrePlay,

    // Control play video
    KRVideoViewPlayControlPlay,

    // Control pause video
    KRVideoViewPlayControlPause,

    // Control stop video
    KRVideoViewPlayControlStop;

    companion object {

        fun from(value: Int): KRVideoViewPlayControl {
            return KRVideoViewPlayControl.values()
                .firstOrNull { it.ordinal == value } ?: KRVideoViewPlayControlNone
        }
    }
}

/**
 * Video playback state
 */
enum class KRVideoPlayState {
    KRVideoPlayStateUnknown,

    // Currently playing (Note: When this state is called back, video should have visible frames)
    KRVideoPlayStatePlaying,

    // Buffering (Note: This state cannot be called if VAVideoPlayStatusPlaying state has not been called)
    KRVideoPlayStateCaching,

    // Playback paused (Note: If a video is in PrepareToPlay state and pause operation is called, this
    // state should be called back)
    KRVideoPlayStatePaused,

    // Playback ended
    KRVideoPlayStatePlayEnd,

    // Playback failed
    KRVideoPlayStateFailed
}

/**
 * Video frame stretch mode
 */
enum class KRVideoViewContentMode(private val value: String) {
    // Display at original video aspect ratio, show vertical if vertical, with black bars on sides
    KRVideoViewContentModeScaleAspectFit("contain"),

    // Stretch video at original aspect ratio until both sides are filled
    KRVideoViewContentModeScaleAspectFill("cover"),

    // Stretch video content to fill borders without maintaining original aspect ratio
    KRVideoViewContentModeScaleToFill("stretch");

    companion object {
        fun from(resizeMode: String): KRVideoViewContentMode {
            return KRVideoViewContentMode.values().firstOrNull { it.value == resizeMode }
                ?: KRVideoViewContentModeScaleAspectFit
        }
    }
}

/**
 * Video object view
 */
class KRVideoView : IKuiklyRenderViewExport {
    // Video instance
    private val video = kuiklyDocument.createElement(ElementType.VIDEO).apply {
        // Listen to playback state related events
        addEventListener("play", {
            // Currently playing
            stateChangeCallback?.invoke(
                mapOf(
                    "state" to KRVideoPlayState.KRVideoPlayStatePlaying.ordinal,
                    "extInfo" to mapOf<String, String>()
                )
            )
        })
        addEventListener("pause", {
            // Paused
            stateChangeCallback?.invoke(
                mapOf(
                    "state" to KRVideoPlayState.KRVideoPlayStatePaused.ordinal,
                    "extInfo" to mapOf<String, String>()
                )
            )
        })
        addEventListener("ended", {
            // Ended
            stateChangeCallback?.invoke(
                mapOf(
                    "state" to KRVideoPlayState.KRVideoPlayStatePlayEnd.ordinal,
                    "extInfo" to mapOf<String, String>()
                )
            )
        })
        addEventListener("waiting", {
            // Buffering
            stateChangeCallback?.invoke(
                mapOf(
                    "state" to KRVideoPlayState.KRVideoPlayStateCaching.ordinal,
                    "extInfo" to mapOf<String, String>()
                )
            )
        })
        addEventListener("error", {
            // Error occurred
            stateChangeCallback?.invoke(
                mapOf(
                    "state" to KRVideoPlayState.KRVideoPlayStateFailed.ordinal,
                    "extInfo" to mapOf<String, String>()
                )
            )
        })
        addEventListener("loadeddata", {
            // First frame loaded
            firstFrameCallback?.invoke(mapOf<String, Any>())
            // 元数据就绪后再补发早到的 seek（否则 seekTo 会被丢弃）
            flushPendingSeek()
        })

        addEventListener("timeupdate", {
            val item = this.unsafeCast<HTMLVideoElement>()
            val currentTime = item.currentTime * 1000
            val totalTime = item.duration * 1000
            // time update
            playTimeChangeCallback?.invoke(
                mapOf(
                    "currentTime" to currentTime.toInt(),
                    "totalTime" to totalTime.toInt(),
                )
            )
        })

        // 缓冲进度：通过 customEvent 通道回传，供进度条绘制「已缓冲」段
        addEventListener("progress", {
            val item = this.unsafeCast<HTMLVideoElement>()
            val buffered = item.buffered
            val bufferedTime = if (buffered.length > 0) buffered.end(buffered.length - 1) * 1000 else 0.0
            val totalTime = if (item.duration.isFinite()) item.duration * 1000 else 0.0
            customEventCallback?.invoke(
                mapOf(
                    "event" to "buffered",
                    "bufferedTime" to bufferedTime.toInt(),
                    "totalTime" to totalTime.toInt(),
                )
            )
        })

        this.unsafeCast<HTMLVideoElement>().apply {
            // Set autoplay
            autoplay = true
            // Set default background color
            style.backgroundColor = "black"
        }
    }

    // First frame loaded callback
    private var firstFrameCallback: KuiklyRenderCallback? = null

    // Playback state change callback
    private var stateChangeCallback: KuiklyRenderCallback? = null

    // Playback time point change callback, currently not in use
    private var playTimeChangeCallback: KuiklyRenderCallback? = null

    // Custom event, currently not in use
    private var customEventCallback: KuiklyRenderCallback? = null

    override val ele: HTMLVideoElement
        get() = video.unsafeCast<HTMLVideoElement>()

    /**
     * 处理 Kuikly 侧的 renderView.callMethod（目前只有全屏）。
     * common `VideoView.setFullscreen()` → `callMethod("setFullscreen", "1"/"0")`。
     */
    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            CALL_SET_FULLSCREEN -> {
                setFullscreen(params == "1")
                null
            }

            else -> super.call(method, params, callback)
        }
    }

    /**
     * Web 全屏。
     *
     * 关键：对**包含 Kuikly 全部 DOM 的根容器**（#root）请求全屏，而不是对 <video> 元素。
     * 若只把 <video> 全屏，浏览器会把它提升到独立的 top layer，Kuikly 绘制的控制条仍留在
     * 普通文档流里，二者不在同一层级 → 全屏后看不到控制条。
     * 全屏 #root 后：视频与控件同处一个全屏层，由页面自身布局决定控件覆盖在视频之上。
     */
    private fun setFullscreen(fullscreen: Boolean) {
        try {
            if (fullscreen) {
                // 对包含 Kuikly 全部 DOM 的根容器请求全屏（不能只全屏 <video>，
                // 否则控件会被留在普通文档流、不在同一层级）
                js("(function(){var el=document.getElementById('root')||document.documentElement;if(el.requestFullscreen){el.requestFullscreen();}})()")
                ele.style.width = "100%"
                ele.style.height = "100%"
            } else {
                js("(function(){if(document.fullscreenElement&&document.exitFullscreen){document.exitFullscreen();}})()")
                ele.style.width = ""
                ele.style.height = ""
            }
        } catch (e: dynamic) {
            Log.warn("KRVideoView setFullscreen error: $e")
        }
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        return when (propKey) {
            SRC -> {
                setVideoPlaySource(propValue.unsafeCast<String?>())
                true
            }

            MUTED -> {
                // Set mute state
                ele.muted = (propValue.unsafeCast<Int>()) == 1
                true
            }

            RATE -> {
                // Set playback rate
                ele.playbackRate = (propValue.unsafeCast<Number>()).toDouble()
                true
            }

            RESIZE_MODE -> {
                // Set background fill mode
                setResizeMode(KRVideoViewContentMode.from(propValue.unsafeCast<String>()))
                true
            }

            PLAY_CONTROL -> {
                playControl(KRVideoViewPlayControl.from(propValue.unsafeCast<Int>()))
                true
            }

            SEEK_TO -> {
                // 公共层 VideoView.seekTo(ms) 下发的显式跳转（此前 Web 端未实现 → 拖动/键盘 seek 全部无效）
                seekToMs(propValue.unsafeCast<Number>().toLong())
                true
            }

            EVENT_PLAY_STATE_CHANGE -> {
                stateChangeCallback = propValue.unsafeCast<KuiklyRenderCallback>()
                true
            }

            EVENT_PLAY_TIME_CHANGE -> {
                playTimeChangeCallback = propValue.unsafeCast<KuiklyRenderCallback>()
                true
            }

            EVENT_FIRST_FRAME -> {
                firstFrameCallback = propValue.unsafeCast<KuiklyRenderCallback>()
                true
            }

            EVENT_CUSTOM_EVENT -> {
                customEventCallback = propValue.unsafeCast<KuiklyRenderCallback>()
                true
            }

            else -> super.setProp(propKey, propValue)
        }
    }

    /** 元数据未就绪时暂存的 seek（毫秒），loadeddata 后补发 */
    private var pendingSeekMs: Long = -1L

    /**
     * 显式跳转（毫秒）。时长未知（元数据未加载）时先暂存，避免 seek 被静默丢弃。
     */
    private fun seekToMs(positionMs: Long) {
        if (positionMs < 0) return
        val dur = video.unsafeCast<HTMLVideoElement>().duration
        if (dur.isNaN() || dur <= 0.0) {
            pendingSeekMs = positionMs
            return
        }
        video.unsafeCast<HTMLVideoElement>().currentTime = (positionMs / 1000.0).coerceIn(0.0, dur)
    }

    private fun flushPendingSeek() {
        val p = pendingSeekMs
        pendingSeekMs = -1L
        if (p >= 0) seekToMs(p)
    }

    /**
     * Set video state
     */
    private fun playControl(state: KRVideoViewPlayControl) {
        when (state) {
            KRVideoViewPlayControl.KRVideoViewPlayControlPrePlay -> {
                // No pre-play capability in web
                Log.warn("there is no prePlay func in web")
            }

            KRVideoViewPlayControl.KRVideoViewPlayControlPlay -> {
                ele.play()
            }

            KRVideoViewPlayControl.KRVideoViewPlayControlPause -> {
                ele.pause()
            }

            KRVideoViewPlayControl.KRVideoViewPlayControlStop -> {
                // No direct stop video and destroy resource in web, so pause first
                ele.pause()
                // Then reset progress to simulate stop
                ele.currentTime = 0.0
            }

            else -> {}
        }
    }

    /**
     * Set video playback source
     */
    private fun setVideoPlaySource(src: String?) {
        // Get playback source, return if not exists
        val source = src ?: return
        // Set playback source
        ele.src = source
    }

    /**
     * Set video frame stretch mode
     */
    private fun setResizeMode(mode: KRVideoViewContentMode) {
        when (mode) {
            KRVideoViewContentMode.KRVideoViewContentModeScaleAspectFit -> {
                ele.style.objectFit = "contain"
            }
            KRVideoViewContentMode.KRVideoViewContentModeScaleAspectFill -> {
                ele.style.objectFit = "cover"
            }
            KRVideoViewContentMode.KRVideoViewContentModeScaleToFill -> {
                ele.style.objectFit = "fill"
            }
        }
    }

    companion object {
        const val VIEW_NAME = "KRVideoView"

        // Methods
        private const val CALL_SET_FULLSCREEN = "setFullscreen"

        // Properties
        private const val SRC = "src"
        private const val MUTED = "muted"
        private const val RATE = "rate"
        private const val RESIZE_MODE = "resizeMode"
        private const val PLAY_CONTROL = "playControl"
        private const val SEEK_TO = "seekTo"

        // Events
        private const val EVENT_PLAY_STATE_CHANGE = "stateChange"
        private const val EVENT_PLAY_TIME_CHANGE = "playTimeChange"
        private const val EVENT_FIRST_FRAME = "firstFrame"
        private const val EVENT_CUSTOM_EVENT = "customEvent"
    }
}
