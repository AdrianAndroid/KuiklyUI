package com.tencent.kuikly.h5app.module

import com.tencent.kuikly.core.render.web.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.web.ktx.KuiklyRenderCallback
import com.tencent.kuikly.core.render.web.nvi.serialization.json.JSONException
import com.tencent.kuikly.core.render.web.nvi.serialization.json.JSONObject
import kotlinx.browser.window
import com.tencent.kuikly.h5app.utils.Ui
import kotlin.js.Date

/**
 * Bridge interface module used by business side
 */
class KRBridgeModule : KuiklyRenderBaseModule() {
    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        val cb = callback
        return when (method) {
            "toast" -> {
                toast(params)
                Unit
            }

            "log" -> {
                console.log(params)
                Unit
            }

            "currentTimestamp" -> {
                currentTimestamp(params)
            }

            // 桌面壳（Electron preload 注入 window.kuiklyHost）：支持把播放页开成独立窗口
            "supportsPlayerWindow" -> {
                val ok = js("(typeof window !== 'undefined' && !!window.kuiklyHost && typeof window.kuiklyHost.openPlayerWindow === 'function')") as Boolean
                if (ok) "{\"supported\":true}" else "{\"supported\":false}"
            }

            // 读取器保存：把内容写到宿主的本地临时文件，返回绝对路径（供 upload(localPath) 使用）
            "saveTempFile" -> {
                val q = js("JSON").parse(params ?: "{}")
                val content = q.content as? String ?: ""
                js("window.localFs.home().then(function(home){ var p = home + '/.kuikly_edit_tmp'; return window.localFs.writeFile(p, content).then(function(){ return p; }); }).then(function(p){ cb({ path: p }); }).catch(function(e){ cb({ path: '' }); })")
                Unit
            }

            "openPlayerWindow" -> {
                val q = js("JSON").parse(params ?: "{}")
                js("window.kuiklyHost.openPlayerWindow(q)")
                Unit
            }

            "dateFormatter" -> {
                dateFormatter(params)
            }

            "readAssetFile" -> {
                val path = js("JSON").parse(params).assetPath
                val url = window.location.protocol + "//" +  window.location.host + "/assets/" + path
                window.fetch(url).then {
                    it.json().then { data->
                        callback?.invoke((mapOf(
                            "result" to JSON.stringify(data)
                        )))
                    }
                }
                Unit
            }

            else -> {
                callback?.invoke(
                    mapOf(
                        "code" to -1,
                        "message" to "Method does not exist"
                    )
                )
                Unit
            }
        }
    }

    /**
     * Show toast message on page
     */
    private fun toast(params: String?) {
        if (params != null) {
            try {
                val message = JSONObject(params)
                Ui.showToast(message)
            } catch (e: JSONException) {
                // JSON parsing failed
                console.error("toast json parse error", e)
            }
        }
    }
    private fun currentTimestamp(params: String?): String = Date.now().toString()

    private fun formatDate(date: Date, format: String): String {
        fun pad(num: Int) = num.toString().padStart(2, '0')
        val replacements = mapOf(
            "yyyy" to date.getFullYear().toString(),
            "MM" to pad(date.getMonth() + 1),
            "dd" to pad(date.getDate()),
            "HH" to pad(date.getHours()),
            "mm" to pad(date.getMinutes()),
            "ss" to pad(date.getSeconds())
        )
        var result = format
        for ((k, v) in replacements) {
            result = result.replace(k, v)
        }
        return result
    }

    private fun dateFormatter(params: String?): String {
        val paramJSONObject = JSONObject(params ?: "{}")
        val date = Date(paramJSONObject.optLong("timeStamp"))
        return formatDate(date, paramJSONObject.optString("format"))
    }

    companion object {
        const val MODULE_NAME = "HRBridgeModule"
    }
}

