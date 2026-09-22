/*
 * Web / MiniApp 平台专用页面注册器。
 *
 * 背景：Kuikly KSP 在 JS/Web/MiniApp 目标上只生成一行页面名注释（供 Gradle 插件的
 * JSProcessor 读取做分包/产物切分），不生成任何「运行时注册」代码；`core-render-web`
 * 自身也从不调用 PagerManager.registerPageRouter。结果 Web/MiniApp 上 pagerNameMap
 * 恒为空，createPager 抛 PagerNotFoundException（白屏）。
 *
 * 解决：demo 与 core 编译在同一个 nativevue2.js bundle 内（demo 依赖 core），且
 * WebMain.kt 提供 main()（webpack 对 executable 入口自动执行）。bundle 加载时
 * 直接注册【SFTP 专题 + demo 入口 router】页面，host 零改动。
 *
 * 注意：仅注册本专题真实可导航页面。原生各端仍走 KSP 生成的 triggerRegisterPages，
 * 不经过这里（本文件只在 jsMain 编译）。
 */
package com.tencent.kuikly.demo.pages.sftp

import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.pager.IPager
import com.tencent.kuikly.demo.pages.router_page.RouterPage
import com.tencent.kuikly.demo.pages.sftp.viewer.SftpViewerDispatcherPage

/**
 * 注册 [pageName] -> [creator] 到 Kuikly 页面注册表（Web/MiniApp 专用）。
 */
private fun registerWebPage(pageName: String, creator: () -> IPager) {
    if (!BridgeManager.isPageExist(pageName)) {
        BridgeManager.registerPageRouter(pageName, creator)
    }
}

private fun diag(msg: String) {
    try {
        console.log("[WebSftpPageRegistry] $msg")
    } catch (_: Throwable) {
    }
}

/**
 * Web/MiniApp 端页面注册入口。由 demo jsMain 入口文件 WebMain.kt 的 main() 在
 * bundle 加载时立即触发（host 页面创建 callKotlinMethod(0) 必然晚于 bundle 加载）。
 */
internal fun registerSftpWebPages() {
    try {
        diag("bridge.init=${BridgeManager.isDidInit()}, pageExist(SftpHomePage)=${BridgeManager.isPageExist("SftpHomePage")}")
        registerWebPage("SftpHomePage") { SftpHomePage() }
        registerWebPage("SftpBrowserPage") { SftpBrowserPage() }
        registerWebPage("SftpConnectEditPage") { SftpConnectEditPage() }
        registerWebPage("SftpPlayerPage") { SftpPlayerPage() }
        registerWebPage("SftpFavoritesPage") { SftpFavoritesPage() }
        registerWebPage("SftpHistoryPage") { SftpHistoryPage() }
        registerWebPage("SftpFilePropsPage") { SftpFilePropsPage() }
        registerWebPage("SftpViewerDispatcherPage") { SftpViewerDispatcherPage() }
        registerWebPage("SftpIntegrationTestPage") { SftpIntegrationTestPage() }
        registerWebPage("SftpBatchProgressDialog") { SftpBatchProgressDialog() }
        // demo 入口页（?page_name 缺省时的默认路由目标）
        registerWebPage("router") { RouterPage() }
        diag("registered; after=${BridgeManager.isPageExist("SftpHomePage")}")
    } catch (t: Throwable) {
        diag("register failed: ${t.stackTraceToString()}")
    }
}