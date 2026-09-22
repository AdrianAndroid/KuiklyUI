package com.tencent.kuikly.h5app

import com.tencent.kuikly.core.render.web.IKuiklyRenderExport
import com.tencent.kuikly.core.render.web.expand.KuiklyRenderViewDelegatorDelegate
import com.tencent.kuikly.core.render.web.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.web.export.IKuiklyRenderViewPropExternalHandler
import com.tencent.kuikly.core.render.web.ktx.SizeI
import com.tencent.kuikly.core.render.web.runtime.web.expand.KuiklyRenderViewDelegator
import com.tencent.kuikly.h5app.components.KRMyView
import com.tencent.kuikly.h5app.components.KRWebView
import com.tencent.kuikly.h5app.components.KuiklyPageView
import com.tencent.kuikly.h5app.module.KRBridgeModule
import com.tencent.kuikly.h5app.module.KRCacheModule
import com.tencent.kuikly.h5app.module.KRLocalMediaProxyModule
import com.tencent.kuikly.h5app.module.KRRouterModule
import com.tencent.kuikly.h5app.module.KRSftpConnectionModule
import com.tencent.kuikly.h5app.module.KRSftpFavoritesModule
import com.tencent.kuikly.h5app.module.KRSftpModule
import com.tencent.kuikly.h5app.module.KRSftpPlaybackHistoryModule

class ViewPropExternalHandler : IKuiklyRenderViewPropExternalHandler {
    override fun setViewExternalProp(
        renderViewExport: IKuiklyRenderViewExport,
        propKey: String,
        propValue: Any
    ): Boolean {
        return when (propKey) {
            "needCustomWrapper" -> {
                renderViewExport.ele.setAttribute("data-needCustomWrapper", propValue.toString())
                true
            }

            else -> false
        }
    }

    override fun resetViewExternalProp(
        renderViewExport: IKuiklyRenderViewExport,
        propKey: String
    ): Boolean {
        return when (propKey) {
            "needCustomWrapper" -> {
                renderViewExport.ele.setAttribute("data-needCustomWrapper", js("undefined"))
                true
            }

            else -> false
        }
    }
}

/**
 * Implement the delegate interface provided by Web Render
 */
class KuiklyWebRenderViewDelegator : KuiklyRenderViewDelegatorDelegate {
    // web render delegate
    private val delegate = KuiklyRenderViewDelegator(this)

    /**
     * Initialize
     */
    fun init(
        containerId: String,
        pageName: String,
        pageData: Map<String, Any>,
        size: SizeI,
    ) {
        // Initialize and create view
        delegate.onAttach(
            containerId,
            pageName,
            pageData,
            size,
        )
    }

    /**
     * Page becomes visible
     */
    fun resume() {
        delegate.onResume()
    }

    /**
     * Page becomes invisible
     */
    fun pause() {
        delegate.onPause()
    }

    /**
     * Page unload
     */
    fun detach() {
        delegate.onDetach()
    }

    /**
     * Page fontLoaded
     */
    fun fontLoaded() {
        delegate.onFontLoaded()
    }

    /**
     * Update the root view size (used on window/container resize).
     */
    fun updateRootViewSize(width: Int, height: Int) {
        delegate.updateRootViewSize(width, height)
    }

    /**
     * Send an event to the current Kuikly page.
     * 页面侧通过 `addPagerEventObserver(IPagerEventObserver)` 接收。
     */
    fun sendEvent(event: String, data: Map<String, Any>) {
        delegate.sendEvent(event, data)
    }

    /**
     * Register custom modules
     */
    override fun registerExternalModule(kuiklyRenderExport: IKuiklyRenderExport) {
        super.registerExternalModule(kuiklyRenderExport)

        // Register bridge module
        kuiklyRenderExport.moduleExport(KRBridgeModule.MODULE_NAME) {
            KRBridgeModule()
        }
        // Register cache module
        kuiklyRenderExport.moduleExport(KRCacheModule.MODULE_NAME) {
            KRCacheModule()
        }

        // rewrite KRRouterModule
        kuiklyRenderExport.moduleExport(KRRouterModule.MODULE_NAME) {
            KRRouterModule()
        }

        // Web SFTP 模块：转发到本地 Node 网关（sftp-gateway），由网关代持真实 SSH/SFTP。
        // 页面（commonMain）无需改动，模块名与 ModuleConst 一致。
        kuiklyRenderExport.moduleExport(KRSftpModule.MODULE_NAME) { KRSftpModule() }
        kuiklyRenderExport.moduleExport(KRSftpConnectionModule.MODULE_NAME) { KRSftpConnectionModule() }
        kuiklyRenderExport.moduleExport(KRSftpFavoritesModule.MODULE_NAME) { KRSftpFavoritesModule() }
        kuiklyRenderExport.moduleExport(KRSftpPlaybackHistoryModule.MODULE_NAME) { KRSftpPlaybackHistoryModule() }
        kuiklyRenderExport.moduleExport(KRLocalMediaProxyModule.MODULE_NAME) { KRLocalMediaProxyModule() }
    }

    fun getKuiklyRenderContext() = delegate.getKuiklyRenderContext()

    override fun registerViewExternalPropHandler(kuiklyRenderExport: IKuiklyRenderExport) {
        super.registerViewExternalPropHandler(kuiklyRenderExport)
        with(kuiklyRenderExport) {
            viewPropExternalHandlerExport(ViewPropExternalHandler())
        }
    }

    override fun registerExternalRenderView(kuiklyRenderExport: IKuiklyRenderExport) {
        super.registerExternalRenderView(kuiklyRenderExport)

        // Register custom views
        kuiklyRenderExport.renderViewExport(KRMyView.VIEW_NAME, {
            KRMyView()
        })
        kuiklyRenderExport.renderViewExport(KuiklyPageView.VIEW_NAME, {
            KuiklyPageView()
        })
        kuiklyRenderExport.renderViewExport(KRWebView.VIEW_NAME, {
            KRWebView()
        })
    }
}
