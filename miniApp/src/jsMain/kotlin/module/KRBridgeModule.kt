package com.tencent.kuikly.miniapp.module

import com.tencent.kuikly.core.render.web.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.web.ktx.KuiklyRenderCallback
import com.tencent.kuikly.core.render.web.nvi.serialization.json.JSONException
import com.tencent.kuikly.core.render.web.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.render.web.runtime.miniapp.MiniGlobal
import com.tencent.kuikly.core.render.web.runtime.miniapp.core.NativeApi
import com.tencent.kuikly.core.render.web.utils.Log
import kotlin.js.json

/**
 * Bridge interface module used by business side
 */
class KRBridgeModule : KuiklyRenderBaseModule() {
    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            "toast" -> {
                showToast(params)
            }

            "log" -> {
                params?.let {
                    Log.log(it)
                }
            }

            "readAssetFile" -> {
                val data = MiniGlobal.globalThis.getAssetJson(js("JSON.parse")(params).assetPath)
                callback?.invoke((mapOf(
                    "result" to JSON.stringify(data)
                )))
            }

            // 能力探测（跨端统一）：小程序无本地文件/终端底座，显式返回不支持（入口隐藏）
            "supportsPlayerWindow" -> "{\"supported\":false}"
            "supportsXterm" -> "{\"supported\":false}"
            "supportsTerminal" -> "{\"supported\":false}"
            // 剪贴板：小程序需走 wx.setClipboardData（渲染层 KRWXClipboardModule，internal 不可跨模块调用）
            // → 本桥不实现，显式不支持，入口隐藏（绝不伪报成功）
            "clipboardSupported" -> "{\"supported\":false}"
            "copyToClipboard" -> Unit
            // 缓存根目录：小程序无宿主本地目录 → 返回空（缓存入口隐藏）
            "cacheRoot" -> "{\"path\":\"\"}"
            "clearCache" -> Unit

            else -> {
                Log.error("$method not found")
                callback?.invoke("{}")
            }
        }
    }

    private fun showToast(params: String?) {
        if (params == null) {
            return
        }
        try {
            val data = JSONObject(params)

            val icon = mapOf(
                1 to "success",
                2 to "error",
                3 to "none"
            )[data.optInt("mode")] ?: "none"

            // NativeApi.plat.showToast = wx.showToast
            NativeApi.plat.showToast(
                json(
                    "title" to data.optString("content"),
                    "icon" to icon
                )
            )
        } catch (e: JSONException) {
            console.error("toast json parse error", e)
        }
    }
    companion object {
        const val MODULE_NAME = "HRBridgeModule"
    }
}
