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

package com.tencent.kuikly.android.demo.module

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.drawable.Drawable
import android.util.Log
import android.widget.Toast
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.tencent.kuikly.android.demo.KRApplication
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderLog
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.math.BigInteger
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Created by kam on 2022/8/11.
 */
class KRBridgeModule : KuiklyRenderBaseModule() {

    private fun testArray(params: Array<Any>, callback: KuiklyRenderCallback?) : Any {

        callback?.invoke(params)
        return params
    }

    override fun call(method: String, params: Any?, callback: KuiklyRenderCallback?): Any? {
        if (method == "testArray") {
            return testArray(params as Array<Any>, callback)
        }
        return super.call(method, params, callback)
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            "showAlert" -> {
                showAlert(params, callback)
            }
            "closePage" -> {
                closePage(params)
            }
            "openPage" -> {
                openPage(params)
            }
            "copyToPasteboard" -> {
                copyToPasteboard(params)
            }
            "toast" -> {
                toast(params)
            }
            "requestOrientation" -> {
                requestOrientation(params)
            }
            "log" -> {
                log(params)
            }
            "reportDT" -> {
                reportDT(params)
            }
            "reportRealtime" -> {
                reportRealtime(params)
            }
            "localServeTime" -> {
                localServeTime(params, callback)
            }
            "currentTimestamp" -> {
                currentTimestamp(params)
            }
            "dateFormatter" -> {
                dateFormatter(params)
            }
            "getLocalImagePath" -> {
                getLocalImagePath(params, callback)
            }
            "readAssetFile" -> {
                readAssetPath(params, callback)
            }
            // 独立窗口播放仅桌面壳（Electron）支持；其它端返回 supported=false，业务侧回退页内路由
            "supportsPlayerWindow" -> "{\"supported\":false}"
            "openPlayerWindow" -> Unit
            // 终端：已接入 KRTerminalModule（JSch shell）→ 支持
            "supportsTerminal" -> "{\"supported\":true}"
            // xterm 仅 Web/桌面；Android 走共享网格渲染
            "supportsXterm" -> "{\"supported\":false}"
            // 剪贴板：Android ClipboardManager
            "clipboardSupported" -> "{\"supported\":true}"
            "copyToClipboard" -> {
                copyToClipboard(params)
            }
            // 本地缓存根目录（缓存整个目录时落盘到应用沙盒）
            "cacheRoot" -> cacheRoot()
            // 清空本地缓存目录
            "clearCache" -> clearCache()
            // 本地文件系统（双栏本地栏）：应用沙盒 filesDir/local，越界拒绝
            "supportsLocalFs" -> "{\"supported\":true}"
            "lfHome" -> {
                callback?.invoke(mapOf("path" to localRoot().absolutePath))
            }
            "lfList" -> {
                lfList(params, callback)
            }
            "lfMkdir" -> {
                val f = resolveWithinLocal(JSONObject(params ?: "{}").optString("path"))
                if (f == null) callback?.invoke(mapOf("error" to "路径越界")) else {
                    if (!f.exists() && !f.mkdirs()) callback?.invoke(mapOf("error" to "创建失败")) else callback?.invoke(mapOf<String, Any>())
                }
            }
            "lfRename" -> {
                val o = JSONObject(params ?: "{}")
                val from = resolveWithinLocal(o.optString("from"))
                val to = resolveWithinLocal(o.optString("to"))
                if (from == null || to == null) callback?.invoke(mapOf("error" to "路径越界"))
                else if (!from.renameTo(to)) callback?.invoke(mapOf("error" to "重命名失败")) else callback?.invoke(mapOf<String, Any>())
            }
            "lfRemove" -> {
                val f = resolveWithinLocal(JSONObject(params ?: "{}").optString("path"))
                if (f == null) callback?.invoke(mapOf("error" to "路径越界")) else {
                    f.deleteRecursively()
                    callback?.invoke(mapOf<String, Any>())
                }
            }

            else -> callback?.invoke(mapOf(
                "code" to -1,
                "message" to "方法不存在"
            ))
        }
    }

    /** 清空应用沙盒内的缓存目录 */
    private fun clearCache() {
        val base = context?.cacheDir ?: KRApplication.application.cacheDir
        File(base, ".kuikly_cache").takeIf { it.exists() }?.deleteRecursively()
    }

    /** 本地文件根目录：应用沙盒 filesDir/local（双栏本地栏） */
    private fun localRoot(): File {
        val base = context?.filesDir ?: KRApplication.application.filesDir
        return File(base, "local").apply { if (!exists()) mkdirs() }
    }

    /** 解析路径并校验在 [localRoot] 内（防越界）；越界返回 null */
    private fun resolveWithinLocal(path: String): File? {
        val root = localRoot().canonicalFile
        val target = if (path.isEmpty()) root else File(path).canonicalFile
        val rootPath = root.path
        return if (target.path == rootPath || target.path.startsWith(rootPath + File.separator)) target else null
    }

    private fun lfList(params: String?, callback: KuiklyRenderCallback?) {
        val dir = resolveWithinLocal(JSONObject(params ?: "{}").optString("path"))
        if (dir == null) {
            callback?.invoke(mapOf("error" to "路径越界"))
            return
        }
        if (!dir.isDirectory) {
            callback?.invoke(mapOf("error" to "不是目录"))
            return
        }
        val arr = JSONArray()
        dir.listFiles()?.forEach { f ->
            val o = JSONObject()
            o.put("name", f.name)
            o.put("type", when {
                f.isDirectory -> "dir"
                f.isFile -> "file"
                else -> "other"
            })
            o.put("size", f.length())
            o.put("mtime", f.lastModified())
            arr.put(o)
        }
        callback?.invoke(mapOf("entries" to arr.toString()))
    }

    /** 复制文本到系统剪贴板（params.text） */
    private fun copyToClipboard(params: String?) {
        val text = JSONObject(params ?: "{}").optString("text")
        (context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.also {
            it.setPrimaryClip(ClipData.newPlainText(MODULE_NAME, text))
        }
    }

    /** 缓存根目录：应用 cacheDir 下的 .kuikly_cache（缓存下载落盘根） */
    private fun cacheRoot(): String {
        val base = context?.cacheDir ?: KRApplication.application.cacheDir
        val dir = File(base, ".kuikly_cache").apply { if (!exists()) mkdirs() }
        return "{\"path\":\"" + dir.absolutePath + "\"}"
    }

    private fun reportRealtime(params: String?) {
    }

    private fun reportDT(params: String?) {
    }

    private fun log(params: String?) {
        if (params == null) {
            return
        }

        val paramJSON = JSONObject(params)
        Log.i("KuiklyRender", paramJSON.optString("content"))
    }

    private fun toast(params: String?) {
        if (params == null) {
            return
        }
        val paramJSON = JSONObject(params)
        Toast.makeText(KRApplication.application,
            paramJSON.optString("content"),
            Toast.LENGTH_SHORT).show()
    }

    private fun requestOrientation(params: String?) {
        val target = JSONObject(params ?: "{}").optString("orientation")
        activity?.requestedOrientation = when (target) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun copyToPasteboard(params: String?) {
        if (params == null) {
            return
        }

        val paramJSON = JSONObject(params)
        (context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.also {
            it.setPrimaryClip(ClipData.newPlainText(MODULE_NAME, paramJSON.optString("content")))
        }
    }

    private fun openPage(params: String?) {
        if (params == null) {
            return
        }
        val ctx = context ?: return
        val paramJSON = JSONObject(params)
        val url = paramJSON.optString("url")
        Log.i(MODULE_NAME, "open page url: $url")
    }

    private fun closePage(params: String?) {
        activity?.finish()
    }

    private fun showAlert(params: String?, callback: KuiklyRenderCallback?) {
        if (params == null) {
            return
        }
        val paramJSON = JSONObject(params)
        val titleText = paramJSON.optString("title")
        val message = paramJSON.optString("message")
        val buttons = paramJSON.optJSONArray("buttons") ?: JSONArray()
    }

    private fun localServeTime(params: String?, callback: KuiklyRenderCallback?) {
        val time = (System.currentTimeMillis() / 1000.0)
        callback?.invoke(mapOf(
            "time" to time
        ))
    }

    private fun currentTimestamp(params: String?): String {
        return (System.currentTimeMillis()).toString()
    }

    private fun dateFormatter(params: String?): String {
        val paramJSONObject = JSONObject(params ?: "{}")
        val data = Date(paramJSONObject.optLong("timeStamp"))
        val format = SimpleDateFormat(paramJSONObject.optString("format"))
        return format.format(data)
    }

    fun getMD5Hash(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val messageDigest = md.digest(input.toByteArray())

        val no = BigInteger(1, messageDigest)
        var hashText = no.toString(16)

        // 补全前导零，以确保哈希值的长度为32位
        while (hashText.length < 32) {
            hashText = "0$hashText"
        }
        return hashText
    }

    private fun getLocalImagePath(params: String?, callback: KuiklyRenderCallback?) {
        val paramJSONObject = JSONObject(params ?: "{}")
        val imageUrl = paramJSONObject.optString("imageUrl")
        Glide.with(context!!)
            .downloadOnly()
            .load(imageUrl)
            .into(object : CustomTarget<File>() {
                override fun onResourceReady(
                    resource: File,
                    transition: Transition<in File>?
                ) {
                    val localFilePath = resource.absolutePath
                    callback?.invoke(mapOf(
                        "localPath" to localFilePath
                    ))
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    callback?.invoke(mapOf<String, String>())
                }

                override fun onLoadFailed(placeholder: Drawable?) {
                    callback?.invoke(mapOf<String, String>())
                }
            })
    }

    private fun readAssetPath(params: String?, callback: KuiklyRenderCallback?) {
        val startTimeMills1 = System.currentTimeMillis()
        KuiklyRenderLog.d("WBDemo", "native readAssetPath startMills: $startTimeMills1")
        Thread{
            try {
                val startTimeMills2 = System.currentTimeMillis()
                val paramJSONObject = JSONObject(params ?: "{}")
                val assetPath = paramJSONObject.optString("assetPath")
                val inputStream = context?.assets?.open(assetPath)
                val reader = BufferedReader(InputStreamReader(inputStream))
                val stringBuilder = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    stringBuilder.append(line)
                }
                reader.close()
                val content = stringBuilder.toString()
                val cost1 = System.currentTimeMillis() - startTimeMills1
                val cost2 = System.currentTimeMillis() - startTimeMills2
                KuiklyRenderLog.d("WBDemo", "readAssetPath cost1: $cost1, cost2: $cost2")
                callback?.invoke(mapOf(
                    "result" to content
                ))
            } catch (e: IOException) {
                e.printStackTrace()
                callback?.invoke(mapOf(
                    "error" to e.message
                ))
            }
        }.start()
    }

    companion object {
        const val MODULE_NAME = "HRBridgeModule"
    }
}

private fun JSONObject.toMap(): Map<Any, Any> {
    val map = mutableMapOf<Any, Any>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        when (val v = opt(key)) {
            is JSONObject -> {
                map[key] = v.toMap()
            }
            else -> {
                v?.also {
                    map[key] = it
                }
            }
        }
    }
    return map
}