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
            //
            // 注意：不要用 `js("...cb(...)")` 引用 Kotlin 局部 `cb` —— Kotlin/JS 下该局部在
            // 生成的 JS 里不可见，运行时报 `TypeError: cb is not a function`，回调永不触发（保存静默失败）。
            // 统一走项目标准写法：Kotlin 里链式 then，回调用 `callback?.invoke(...)`。
            "saveTempFile" -> {
                val q = js("JSON").parse(params ?: "{}")
                val content = q.content as? String ?: ""
                saveTempFileAsync(content, cb)
                Unit
            }

            // 终端：Web/桌面由宿主 xterm + 本地网关提供
            "supportsTerminal" -> {
                val ok = js("(typeof window !== 'undefined' && typeof window.__krTerm !== 'undefined')") as Boolean
                if (ok) "{\"supported\":true}" else "{\"supported\":true}"   // 网关可用即可（网格渲染不依赖 xterm）
            }

            // xterm.js 加速（Web/桌面）：挂载/写入/缩放/销毁，见 resources/lib/kr-terminal.js
            "supportsXterm" -> {
                val ok = js("(typeof window !== 'undefined' && typeof window.__krTerm !== 'undefined' && typeof window.__krTerm.mount === 'function')") as Boolean
                if (ok) "{\"supported\":true}" else "{\"supported\":false}"
            }

            "xtermMount" -> {
                val q = js("JSON").parse(params ?: "{}")
                val res = js("window.__krTerm.mount({ x: q.x, y: q.y, width: q.width, height: q.height, fontSize: q.fontSize })")
                val id = ((res.termId as? String) ?: "").replace("\"", "")
                val cols = (res.cols as? Number)?.toInt() ?: 80
                val rows = (res.rows as? Number)?.toInt() ?: 24
                "{\"termId\":\"$id\",\"cols\":$cols,\"rows\":$rows}"
            }

            "xtermWrite" -> {
                val q = js("JSON").parse(params ?: "{}")
                js("window.__krTerm.write(q.termId, q.data)")
                Unit
            }

            "xtermResize" -> {
                val q = js("JSON").parse(params ?: "{}")
                js("window.__krTerm.resize(q.termId, q.width, q.height)")
                Unit
            }

            "xtermDispose" -> {
                val q = js("JSON").parse(params ?: "{}")
                js("window.__krTerm.dispose(q.termId)")
                Unit
            }

            "xtermSetVisible" -> {
                val q = js("JSON").parse(params ?: "{}")
                js("window.__krTerm.setVisible(q.termId, q.visible)")
                Unit
            }

            // 剪贴板（Web/桌面）：优先 navigator.clipboard，失败回退 textarea+execCommand
            "clipboardSupported" -> {
                val ok = js("(typeof navigator !== 'undefined' && (!!(navigator.clipboard && navigator.clipboard.writeText) || !!document.execCommand))") as Boolean
                if (ok) "{\"supported\":true}" else "{\"supported\":false}"
            }

            "copyToClipboard" -> {
                val q = js("JSON").parse(params ?: "{}")
                val text = (q.text as? String) ?: ""
                js("window.__krCopyText = text")
                js(
                    "(function(){try{var t=window.__krCopyText||'';" +
                        "if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(t);return;}" +
                        "var ta=document.createElement('textarea');ta.value=t;ta.style.position='fixed';ta.style.opacity='0';" +
                        "document.body.appendChild(ta);ta.select();document.execCommand('copy');document.body.removeChild(ta);" +
                        "}catch(e){}})()"
                )
                Unit
            }

            // 清空本地缓存目录（Web/桌面：<home>/.kuikly_cache）
            "clearCache" -> {
                js("(function(){try{if(!window.localFs)return;window.localFs.home().then(function(h){return window.localFs.remove(h+'/.kuikly_cache',true);}).catch(function(e){});}catch(e){}})()")
                Unit
            }

            // 本地文件系统（双栏本地栏）：转发到宿主 window.localFs（Electron preload 提供；纯 H5 无）
            "supportsLocalFs" -> {
                val ok = js("(typeof window !== 'undefined' && !!window.localFs && typeof window.localFs.list === 'function')") as Boolean
                if (ok) "{\"supported\":true}" else "{\"supported\":false}"
            }
            "lfHome" -> {
                val lf = js("(typeof window !== 'undefined' && window.localFs) ? window.localFs : null")
                if (lf == null) {
                    callback?.invoke(mapOf("error" to "本地文件不可用：请在桌面版（Electron）中使用"))
                } else {
                    lf.home().then(
                        { p: dynamic -> callback?.invoke(mapOf("path" to (p as? String ?: ""))) },
                        { e: dynamic -> callback?.invoke(mapOf("error" to ((e.message as? String) ?: "获取主目录失败"))) },
                    )
                }
                Unit
            }
            "lfList" -> {
                val q = js("JSON").parse(params ?: "{}")
                val lf = js("(typeof window !== 'undefined' && window.localFs) ? window.localFs : null")
                if (lf == null) {
                    callback?.invoke(mapOf("error" to "本地文件不可用（非桌面宿主）"))
                } else {
                    lf.list(q.path).then(
                        { r: dynamic -> callback?.invoke(mapOf("entries" to js("JSON.stringify(r.entries)"))) },
                        { e: dynamic -> callback?.invoke(mapOf("error" to ((e.message as? String) ?: "读取目录失败"))) },
                    )
                }
                Unit
            }
            "lfMkdir" -> {
                val q = js("JSON").parse(params ?: "{}")
                val lf = js("(typeof window !== 'undefined' && window.localFs) ? window.localFs : null")
                if (lf == null) {
                    callback?.invoke(mapOf("error" to "本地文件不可用"))
                } else {
                    lf.mkdir(q.path).then(
                        { _: dynamic -> callback?.invoke(mapOf<String, Any>()) },
                        { e: dynamic -> callback?.invoke(mapOf("error" to ((e.message as? String) ?: "创建失败"))) },
                    )
                }
                Unit
            }
            "lfRename" -> {
                val q = js("JSON").parse(params ?: "{}")
                val lf = js("(typeof window !== 'undefined' && window.localFs) ? window.localFs : null")
                if (lf == null) {
                    callback?.invoke(mapOf("error" to "本地文件不可用"))
                } else {
                    lf.rename(q.from, q.to).then(
                        { _: dynamic -> callback?.invoke(mapOf<String, Any>()) },
                        { e: dynamic -> callback?.invoke(mapOf("error" to ((e.message as? String) ?: "重命名失败"))) },
                    )
                }
                Unit
            }
            "lfRemove" -> {
                val q = js("JSON").parse(params ?: "{}")
                val lf = js("(typeof window !== 'undefined' && window.localFs) ? window.localFs : null")
                if (lf == null) {
                    callback?.invoke(mapOf("error" to "本地文件不可用"))
                } else {
                    lf.remove(q.path, true).then(
                        { _: dynamic -> callback?.invoke(mapOf<String, Any>()) },
                        { e: dynamic -> callback?.invoke(mapOf("error" to ((e.message as? String) ?: "删除失败"))) },
                    )
                }
                Unit
            }

            // 本地缓存根目录（缓存整个目录时作为落盘根）
            // 必须**同步**返回：Kuikly 的 cacheRoot 走同步通道，异步 home() 拿不到值（会恒为空）。
            // 桌面壳 preload 暴露 homeSync（sendSync）；纯浏览器无 window.localFs → 空串（不支持缓存）。
            "cacheRoot" -> {
                val home = js("(typeof window !== 'undefined' && window.localFs && typeof window.localFs.homeSync === 'function') ? String(window.localFs.homeSync()) : ''") as String
                if (home.isNotEmpty()) "{\"path\":\"" + home + "/.kuikly_cache\"}" else "{\"path\":\"\"}"
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
     * 把 base64 内容写入宿主本地临时文件（`window.localFs`，由 Electron preload 注入），
     * 完成后经 [cb] 回传 `{"path": "<绝对路径>"}`（失败回传空 path）。
     *
     * 用 Kotlin 链式 `.then`（lambda 编译成 JS 函数）而不是 `js("...cb(...)")`，
     * 避免局部回调在生成的 JS 里不可见。
     */
    private fun saveTempFileAsync(content: String, cb: KuiklyRenderCallback?) {
        try {
            val lf = js("window.localFs").unsafeCast<LocalFsJs>()
            lf.home().then({ home ->
                val p = "$home/.kuikly_edit_tmp"
                lf.writeFile(p, content).then(
                    { ignoredOk -> cb?.invoke("{\"path\":\"$p\"}") },
                    { ignoredErr -> cb?.invoke("{\"path\":\"\"}") }
                )
            }, { ignoredErr -> cb?.invoke("{\"path\":\"\"}") })
        } catch (e: Throwable) {
            cb?.invoke("{\"path\":\"\"}")
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


/** `window.localFs`（Electron preload 注入）的最小 JS 类型声明，用于保存临时文件。 */
private external class LocalFsJs {
    fun home(): JsPromise
    fun writeFile(path: String, contentBase64: String): JsPromise
}

/** Promise 的最小声明：只需要 then。 */
private external class JsPromise {
    fun then(onFulfilled: (dynamic) -> Unit): JsPromise
    fun then(onFulfilled: (dynamic) -> Unit, onRejected: (dynamic) -> Unit): JsPromise
}
