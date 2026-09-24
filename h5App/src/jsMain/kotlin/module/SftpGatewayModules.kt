package com.tencent.kuikly.h5app.module

import com.tencent.kuikly.core.render.web.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.web.ktx.KuiklyRenderCallback
import com.tencent.kuikly.core.render.web.nvi.serialization.json.JSONObject
import kotlinx.browser.window
import org.w3c.fetch.RequestInit

/**
 * Web(H5) 版 SFTP 模块 —— 浏览器不能建立原始 TCP/SSH，所以这里不做协议，只做「桥」：
 * 把 Kuikly 的 Module 调用转发给本地 Node 网关（sftp-gateway），由网关代持真实 SSH/SFTP。
 *
 * 网关地址：默认 http://127.0.0.1:18090，可在宿主页面用
 *   `window.__SFTP_GATEWAY_URL__ = 'http://...'` 覆盖。
 *
 * 模块名与 common `ModuleConst` 完全对应，页面无需任何改动：
 *   KRSftpModule / KRSftpConnectionModule / KRSftpFavoritesModule /
 *   KRSftpPlaybackHistoryModule / KRLocalMediaProxyModule
 */

internal fun sftpGatewayUrl(): String =
    js("(typeof window !== 'undefined' && window.__SFTP_GATEWAY_URL__) || 'http://127.0.0.1:18090'") as String

internal fun sftpGatewayRpc(
    module: String,
    method: String,
    paramsJson: String?,
    callback: KuiklyRenderCallback?
) {
    val p = if (paramsJson.isNullOrEmpty()) "{}" else paramsJson
    val body = "{\"module\":\"$module\",\"method\":\"$method\",\"params\":$p}"
    val init = js("({ method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body })")
    window.fetch(sftpGatewayUrl() + "/rpc", init.unsafeCast<RequestInit>())
        .then { resp -> resp.text() }
        .then { text ->
            callback?.invoke(text)
            Unit
        }
}

/** 除 `read`(原子通道) 外，所有 SFTP 方法的通用转发实现。 */
open class SftpGatewayProxyModule(private val remoteModule: String) : KuiklyRenderBaseModule() {
    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        sftpGatewayRpc(remoteModule, method, params, callback)
        return null
    }
}

/** 主模块：唯一多一个 `read`（原子通道，回参 [meta, ByteArray]）需要特判。 */
class KRSftpModule : SftpGatewayProxyModule("sftp") {
    override fun call(method: String, params: Any?, callback: KuiklyRenderCallback?): Any? {
        if (method == METHOD_READ) {
            val arr = params.asDynamic()
            val fileHandleId = arr[0] as? String ?: return null
            val offset = (arr[1] as? Number)?.toLong() ?: 0L
            val length = (arr[2] as? Number)?.toInt() ?: 0
            val p = "{\"fileHandleId\":\"$fileHandleId\",\"offset\":$offset,\"length\":$length}"
            val cb = KuiklyRenderCallback { res: Any? ->
                val text = (res as? String) ?: "{}"
                val b64 = try {
                    JSONObject(text).optString("base64")
                } catch (e: dynamic) {
                    ""
                }
                val meta = JSONObject()
                meta.put("ok", true)
                callback?.invoke(arrayOf<Any?>(meta, base64ToByteArray(b64)))
                Unit
            }
            sftpGatewayRpc("sftp", METHOD_READ, p, cb)
            return null
        }
        return super.call(method, params, callback)
    }

    private fun base64ToByteArray(b64: String): ByteArray {
        if (b64.isEmpty()) return ByteArray(0)
        val bin = js("atob(b64)") as String
        val out = ByteArray(bin.length)
        for (i in bin.indices) out[i] = bin[i].code.toByte()
        return out
    }

    companion object {
        const val MODULE_NAME = "KRSftpModule"
        private const val METHOD_READ = "read"
    }
}

class KRSftpConnectionModule : SftpGatewayProxyModule("connection") {
    companion object { const val MODULE_NAME = "KRSftpConnectionModule" }
}

class KRSftpFavoritesModule : SftpGatewayProxyModule("favorites") {
    companion object { const val MODULE_NAME = "KRSftpFavoritesModule" }
}

class KRSftpPlaybackHistoryModule : SftpGatewayProxyModule("history") {
    companion object { const val MODULE_NAME = "KRSftpPlaybackHistoryModule" }
}

/** 媒体代理：Web 端由网关直接以 HTTP Range 提供流，这里只负责注册/注销 token 与端口。 */
class KRLocalMediaProxyModule : SftpGatewayProxyModule("mediaProxy") {
    companion object { const val MODULE_NAME = "KRLocalMediaProxyModule" }
}

/**
 * 终端（shell）模块：转发到网关的 `shell` 模块。
 * 远程走 ssh2 `conn.shell()`（pty），本地走宿主 pty；输出用「偏移轮询」，无需 WebSocket。
 */
class KRTerminalModule : SftpGatewayProxyModule("shell")
