package com.tencent.kuikly.h5app

import com.tencent.kuikly.core.render.web.expand.module.KRNotifyModule
import com.tencent.kuikly.core.render.web.processor.KuiklyProcessor
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.events.Event
import com.tencent.kuikly.h5app.manager.KuiklyRouter
import com.tencent.kuikly.h5app.processor.CustomImageProcessor

/**
 * WebApp entry, use renderView delegate method to initialize and create renderView
 */
fun main() {
    // Configure whether to prevent default text selection and image drag behavior.
    // Set to false to allow text selection and image dragging.
    // KuiklyProcessor.preventDefaultDragAndSelect = false

    // Takes over control if "use_spa=1" is present in URL or ENABLE_BY_DEFAULT is true
    if (KuiklyRouter.handleEntry()) {
        return
    }

    console.log("##### Kuikly H5 #####")

    // Create and initialize the page delegator using shared logic
    val delegator = KuiklyRouter.createDelegator(window.location.href)

    // 窗口尺寸变化时更新根视图尺寸。只在尺寸真的变化时下发，避免与拖动手势互相干扰。
    // （不用 Kuikly 的 autoUpdateRootViewSizeOnResize：它基于 ResizeObserver，
    //   会在手势/内容变化时也可能触发重排。）
    var lastW = window.innerWidth
    var lastH = window.innerHeight
    window.addEventListener("resize", {
        val w = window.innerWidth
        val h = window.innerHeight
        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            delegator.updateRootViewSize(w, h)
        }
    })

    // 把宿主「活动」转成页面事件：桌面 Web 用于全屏播放时「动一下鼠标就显示控制条」。
    // 移动端没有鼠标移动，由页面靠触摸/点击处理，逻辑一致。
    var lastActivity = 0.0
    val onActivity = {
        val now = js("Date.now()") as Double
        if (now - lastActivity > 250) {   // 节流，避免 mousemove 高频发包
            lastActivity = now
            delegator.sendEvent("sftp_controls_activity", emptyMap<String, Any>())
        }
    }
    document.addEventListener("mousemove", { onActivity() })
    document.addEventListener("touchstart", { onActivity() })

    // 用户按 ESC 退出全屏时，把状态回传页面，避免页面 isFullscreen 与实际不一致。
    document.addEventListener("fullscreenchange", {
        val fs = js("document.fullscreenElement != null") as Boolean
        delegator.sendEvent("sftp_fullscreen_changed", mapOf<String, Any>("fullscreen" to fs))
    })

    // 键盘快捷键（桌面 Web）：空格/K 播放暂停、←/→ 快退快进、M 静音、F 全屏。
    // 输入框中不拦截。对齐 Plyr 的常用键位。
    document.addEventListener("keydown", { e ->
        val ev = e.asDynamic()
        val tag = js("(document.activeElement && document.activeElement.tagName) || ''") as String
        if (tag != "INPUT" && tag != "TEXTAREA") {
            val key = (ev.key as? String) ?: ""
            if (key.isNotEmpty()) {
                delegator.sendEvent("sftp_player_key", mapOf<String, Any>("key" to key))
            }
        }
    })

    // modify image cdn
//    KuiklyProcessor.imageProcessor = CustomImageProcessor

    // Register visibility event
    document.addEventListener("visibilitychange", {
        val hidden = document.asDynamic().hidden as Boolean
        if (hidden) {
            // Page hidden
            delegator.pause()
        } else {
            // Page restored
            delegator.resume()
        }
    })

    // Register Kuikly event listener for Web host to receive events from Kuikly pages
    // When Kuikly page calls NotifyModule.postNotify(), Web host can receive the event here
    registerKuiklyEventListener()

    // When using custom fonts, fonts are loaded asynchronously, so a re-layout needs to be 
    // triggered after loading completes to re-measure text with the correct font metrics
    // document.asDynamic().fonts.load("16px 'Kanit Medium'").then({ _ ->
    //     delegator.fontLoaded()
    // })
}

/**
 * Register listener to receive events from Kuikly pages
 * 
 * Usage in Kuikly page:
 * ```kotlin
 * acquireModule<NotifyModule>(NotifyModule.MODULE_NAME)
 *     .postNotify("your_event_name", JSONObject().apply { put("key", "value") })
 * ```
 */
private fun registerKuiklyEventListener() {
    window.addEventListener("kuikly_to_host_event", { event: Event ->
        val detail = event.asDynamic().detail
        val eventName = detail.eventName as? String ?: ""
        val data = detail.data as? String ?: "{}"
        
        console.log("[Web Host] Received Kuikly event: $eventName")
        console.log("[Web Host] Event data: $data")
    })
    console.log("[Web Host] Kuikly event listener registered")
}
