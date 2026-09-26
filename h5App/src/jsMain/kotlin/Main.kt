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

    // ⚠️ 必须在 handleEntry() 之前安装：SPA 模式下 handleEntry() 会 return true 提前退出，
    //    否则事件桥（含下列监听与 __kuiklySendEvent__）永远不会生效。
    installHostEventBridges()

    // Takes over control if "use_spa=1" is present in URL or ENABLE_BY_DEFAULT is true
    if (KuiklyRouter.handleEntry()) {
        return
    }

    console.log("##### Kuikly H5 #####")

    // Web 端的 Pager 注册由业务 bundle（nativevue2.js）提供：KSP 在 JS 目标只生成
    // 页面名注释，不生成运行时注册代码，因此这里必须在创建页面（callKotlinMethod(0)）
    // 之前显式调用注册入口（幂等，见 demo/src/jsMain/.../WebSftpPageRegistry.kt）。
    try {
        val g = window.asDynamic()
        val registerFn = g.com?.tencent?.kuikly?.demo?.pages?.sftp?.registerKuiklyDemoWebPages
        if (registerFn != null) {
            registerFn()
            console.log("##### Kuikly H5: demo pages registered #####")
        } else {
            console.warn("##### Kuikly H5: registerKuiklyDemoWebPages not found #####")
        }
    } catch (e: Throwable) {
        console.warn("##### Kuikly H5: register demo pages failed: $e #####")
    }

    // Create and initialize the page delegator using shared logic
    val delegator = KuiklyRouter.createDelegator(window.location.href)

    KuiklyRouter.fallbackDelegator = delegator

    // 窗口 resize 监听已统一在 installHostEventBridges() 安装（SPA 也生效），此处不再重复安装。

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

/**
 * 安装「宿主 → 页面」事件桥。Web 与 Electron 桌面壳可共用同一套页面事件。
 * 通过 window.__kuiklySendEvent__(event, json) 对外暴露发送口。
 */
private fun installHostEventBridges() {
    window.asDynamic().__kuiklySendEvent__ = { ev: String, dataJson: String ->
        val data: Map<String, Any> = if (dataJson.isNullOrBlank()) {
            emptyMap()
        } else {
            com.tencent.kuikly.core.render.web.nvi.serialization.json.JSONObject(dataJson).toMap()
        }
        KuiklyRouter.sendEventToCurrentPage(ev, data)
        Unit
    }

    // 宿主活动 → 唤醒控制条（桌面：鼠标移动；移动端：触摸）
    var lastActivity = 0.0
    val onActivity = {
        val now = js("Date.now()") as Double
        if (now - lastActivity > 250) {
            lastActivity = now
            KuiklyRouter.sendEventToCurrentPage("sftp_controls_activity", emptyMap())
        }
    }
    document.addEventListener("mousemove", { onActivity() })
    document.addEventListener("touchstart", { onActivity() })

    // ESC 退出全屏时同步状态
    document.addEventListener("fullscreenchange", {
        val fs = js("document.fullscreenElement != null") as Boolean
        KuiklyRouter.sendEventToCurrentPage("sftp_fullscreen_changed", mapOf("fullscreen" to fs))
    })

    // 键盘快捷键：空格/K、←/→、M、F（输入框内不拦截）
    document.addEventListener("keydown", { e ->
        val ev = e.asDynamic()
        val tag = js("(document.activeElement && document.activeElement.tagName) || ''") as String
        if (tag != "INPUT" && tag != "TEXTAREA") {
            val key = (ev.key as? String) ?: ""
            if (key.isNotEmpty()) {
                KuiklyRouter.sendEventToCurrentPage("sftp_player_key", mapOf("key" to key))
                // 通用键盘事件：回车=确定（供各页确认弹窗监听；页面自行判断是否消费）
                if (key == "Enter") {
                    KuiklyRouter.sendEventToCurrentPage("host_key", mapOf("key" to "Enter"))
                }
            }
        }
    })

    // 窗口尺寸变化 → 下发 rootViewSizeDidChanged（SPA 与非 SPA 都生效）。
    // 必须在这里安装：main() 在 SPA 模式会提前 return，之前的监听装在 return 之后 → 桌面端永不生效，
    // 表现为「缩放窗口后视频/布局不跟随」。
    var lastW = window.innerWidth
    var lastH = window.innerHeight
    window.addEventListener("resize", {
        val w = window.innerWidth
        val h = window.innerHeight
        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            KuiklyRouter.updateRootViewSizeForActive(w, h)
        }
    })
}

