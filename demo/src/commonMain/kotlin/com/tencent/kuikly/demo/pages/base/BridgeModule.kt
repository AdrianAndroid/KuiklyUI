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

package com.tencent.kuikly.demo.pages.base

import com.tencent.kuikly.core.base.toInt
import com.tencent.kuikly.core.log.KLog
import com.tencent.kuikly.core.module.CallbackFn
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

internal class BridgeModule : Module() {

    override fun moduleName(): String {
        return MODULE_NAME
    }

    /**
     * 桌面壳（Electron）：是否支持把播放页开成**独立窗口**（可同时播放多个视频）。
     * 其它端实现返回 supported=false，业务侧自动回退为页内路由。
     */
    fun supportsPlayerWindow(): Boolean {
        val res = syncToNativeMethod(SUPPORTS_PLAYER_WINDOW, JSONObject(), null)
        return runCatching { JSONObject(res).optBoolean("supported", false) }
            .getOrDefault(res.trim() == "true")
    }

    /** 桌面壳（Electron）：把播放页开成独立窗口。参数即播放页 pageData。 */
    fun openPlayerWindow(playerParams: JSONObject) {
        callNativeMethod(OPEN_PLAYER_WINDOW, playerParams, null)
    }

    /**
     * 桌面/Web 宿主：把内容写入宿主的本地临时文件并返回绝对路径。
     * 读取器保存时用「写临时文件 + SftpModule.upload(localPath)」，避免新增各端原生方法。
     * 仅当 [supportsPlayerWindow] 为 true（Web/桌面壳）时才调用 → 原生端不需要实现。
     */
    fun saveTempFile(contentBase64: String, callback: (path: String?) -> Unit) {
        val args = JSONObject()
        args.put("content", contentBase64)
        callNativeMethod(SAVE_TEMP_FILE, args) { data ->
            callback(data?.optString("path")?.takeIf { it.isNotEmpty() })
        }
    }

    /**
     * 本端是否支持终端（远程/本地 shell）。
     * Web/桌面：由宿主 xterm 与本地网关提供 → true；
     * 原生端：需实现 `KRTerminalModule`（libssh2 pty）后再返回 true（当前 false → 页面隐藏入口并给出提示）。
     */
    fun supportsTerminal(): Boolean {
        val res = syncToNativeMethod(SUPPORTS_TERMINAL, JSONObject(), null)
        return runCatching { JSONObject(res).optBoolean("supported", false) }
            .getOrDefault(res.trim() == "true")
    }

    /**
     * 本地缓存根目录（用于「缓存整个目录」的落盘位置）。
     * Web/桌面返回宿主本地目录（如 `<home>/.kuikly_cache`）；其它端返回空串（走各端沙盒默认目录）。
     */
    fun cacheRoot(): String {
        val res = syncToNativeMethod(CACHE_ROOT, JSONObject(), null)
        return runCatching { JSONObject(res).optString("path") }.getOrDefault("")
    }

    /**
     * 本端是否支持 xterm.js 加速渲染（Web/桌面）。
     * 其它端 false → 终端页回退共享网格渲染（`TerminalGridView`，六端共用）。
     */
    fun supportsXterm(): Boolean {
        val res = syncToNativeMethod(SUPPORTS_XTERM, JSONObject(), null)
        return runCatching { JSONObject(res).optBoolean("supported", false) }.getOrDefault(res.trim() == "true")
    }

    /** 在宿主挂载 xterm 终端（绝对定位到给定矩形），同步返回 termId 与行列数。仅 [supportsXterm] 为 true 时调用。 */
    fun xtermMount(x: Float, y: Float, width: Float, height: Float, fontSize: Float, callback: (termId: String, cols: Int, rows: Int) -> Unit) {
        val args = JSONObject()
        args.put("x", x.toDouble())
        args.put("y", y.toDouble())
        args.put("width", width.toDouble())
        args.put("height", height.toDouble())
        args.put("fontSize", fontSize.toDouble())
        val res = syncToNativeMethod(XTERM_MOUNT, args, null)
        val obj = runCatching { JSONObject(res) }.getOrDefault(JSONObject())
        callback(obj.optString("termId"), obj.optInt("cols", 80), obj.optInt("rows", 24))
    }

    /** 把终端输出（base64 字节）写入 xterm */
    fun xtermWrite(termId: String, base64Data: String) {
        val args = JSONObject()
        args.put("termId", termId)
        args.put("data", base64Data)
        callNativeMethod(XTERM_WRITE, args, null)
    }

    /** 重新定位/缩放 xterm（容器尺寸变化时） */
    fun xtermResize(termId: String, width: Float, height: Float) {
        val args = JSONObject()
        args.put("termId", termId)
        args.put("width", width.toDouble())
        args.put("height", height.toDouble())
        callNativeMethod(XTERM_RESIZE, args, null)
    }

    /** 销毁 xterm（页面退出时） */
    fun xtermDispose(termId: String) {
        val args = JSONObject()
        args.put("termId", termId)
        callNativeMethod(XTERM_DISPOSE, args, null)
    }

    /** 显示/隐藏 xterm（Kuikly 浮层需要盖在终端之上时） */
    fun xtermSetVisible(termId: String, visible: Boolean) {
        val args = JSONObject()
        args.put("termId", termId)
        args.put("visible", visible)
        callNativeMethod(XTERM_SET_VISIBLE, args, null)
    }

    /** 本端是否支持复制到剪贴板（Web/桌面 true；其它端 false → 入口隐藏） */
    fun supportsClipboard(): Boolean {
        val res = syncToNativeMethod(CLIPBOARD_SUPPORTED, JSONObject(), null)
        return runCatching { JSONObject(res).optBoolean("supported", false) }.getOrDefault(false)
    }

    /** 复制文本到系统剪贴板 */
    fun copyToClipboard(text: String) {
        val args = JSONObject()
        args.put("text", text)
        callNativeMethod(COPY_TO_CLIPBOARD, args, null)
    }

    fun toast(content: String) {
        val methodArgs = JSONObject()
        methodArgs.put("content", content)
        callNativeMethod("toast", methodArgs, null)
    }

    fun requestLandscape() {
        requestOrientation("landscape")
    }

    fun requestPortrait() {
        requestOrientation("portrait")
    }

    private fun requestOrientation(orientation: String) {
        val methodArgs = JSONObject()
        methodArgs.put("orientation", orientation)
        callNativeMethod(REQUEST_ORIENTATION, methodArgs, null)
    }

    fun testArray() {
        //call
        val array = arrayOf<Any>("222", createByteArray())
        val res = syncToNativeMethod("testArray", array) {
            if (it is Array<*>) {
                KLog.i(
                    "testArray",
                    "callback res:${it[1] is ByteArray} ${(it[1] as ByteArray)[1].toString()}" + it.toString()
                )

            }
        }

        var i = 0

        if (res is Array<*>) {
            i = res.size

            KLog.i(
                "testArray res:",
                "${res[1] is ByteArray} | ${(res[1] as ByteArray).size}" + res[0].toString()
            )

        }
    }

    fun createByteArray(): ByteArray {
        val size = 10 // 指定 ByteArray 的大小
        val byteArray = ByteArray(size)

        for (i in 0 until size) {
            byteArray[i] = (i * 2).toByte() // 每个元素的值为其索引的两倍
        }

        return byteArray
    }

    fun openPage(
        url: String,
        closeCurPage: Boolean = false,
        closeSamePage: Boolean = false,
        userData: JSONObject? = null,
        callbackFn: CallbackFn? = null
    ) {
        val methodArgs = JSONObject()
        methodArgs.put("url", url)
        methodArgs.put("closeCurPage", closeCurPage.toInt())
        methodArgs.put("closeSamePage", closeSamePage.toInt())
        userData?.also {
            methodArgs.put("userData", it)
        }
        callNativeMethod(OPEN_PAGE, methodArgs, callbackFn)
    }

    // 同步获取时间戳（毫秒）
    // 注：一般不用于业务，仅为本地性能耗时测试
    fun currentTimeStamp(): Long {
        val timestamp = syncCallNativeMethod(CURRENT_TIMESTAMP, null, null)
        if (timestamp.isNotEmpty()) {
            return timestamp.toLong()
        } else {
            return 0
        }
    }

    // 同步获取日期格式化
    fun dateFormatter(timeStamp: Long, format: String): String {
        val params = JSONObject()
        params.put("timeStamp", timeStamp)
        params.put("format", format)
        return syncCallNativeMethod(DATE_FORMATTER, params, null)
    }

    /**
     * 获取指定 url 的本地缓存地址
     */
    fun getLocalImagePath(url: String, callback: CallbackFn?) {
        val params = JSONObject()
        params.put("imageUrl", url)
        callNativeMethod(GET_LOCAL_IMAGE_PATH, params, callback)
    }

    /**
     * 读取文件内容
     */
    fun readAssetFile(assetPath: String, callback: CallbackFn?) {
        val params = JSONObject()
        params.put("assetPath", assetPath)
        syncCallNativeMethod(READ_ASSET_FILE, params, callback)
    }

    /**
     * 同步读取文件内容（直接通过同步通道返回，从 kuikly 线程同步等待主线程返回）
     * @param assetPath asset 文件路径
     * @param repeatCount 端侧反复读取的次数，用于模拟慢同步调用，默认 50
     * @return 文件内容字符串；如果读取失败则返回空字符串
     */
    fun readAssetFileSync(assetPath: String, repeatCount: Int = 50): String {
        val params = JSONObject()
        params.put("assetPath", assetPath)
        params.put("repeatCount", repeatCount)
        return syncCallNativeMethod(READ_ASSET_FILE_SYNC, params, null)
    }

    private fun callNativeMethod(methodName: String, data: JSONObject?, callbackFn: CallbackFn?) {
        toNative(
            false,
            methodName,
            data?.toString(),
            callbackFn,
            false
        )
    }

    // --------- 同步调用Native方法 -------
    private fun syncCallNativeMethod(
        methodName: String,
        data: JSONObject?,
        callbackFn: CallbackFn?
    ): String {
        return toNative(
            false,
            methodName,
            data?.toString(),
            callbackFn,
            true
        ).toString()
    }

    companion object {
        const val SUPPORTS_PLAYER_WINDOW = "supportsPlayerWindow"
        const val OPEN_PLAYER_WINDOW = "openPlayerWindow"
        const val SAVE_TEMP_FILE = "saveTempFile"
        const val SUPPORTS_TERMINAL = "supportsTerminal"
        const val CACHE_ROOT = "cacheRoot"
        const val SUPPORTS_XTERM = "supportsXterm"
        const val XTERM_MOUNT = "xtermMount"
        const val XTERM_WRITE = "xtermWrite"
        const val XTERM_RESIZE = "xtermResize"
        const val XTERM_DISPOSE = "xtermDispose"
        const val XTERM_SET_VISIBLE = "xtermSetVisible"
        const val CLIPBOARD_SUPPORTED = "clipboardSupported"
        const val COPY_TO_CLIPBOARD = "copyToClipboard"

        const val MODULE_NAME = "HRBridgeModule"
        const val OPEN_PAGE = "openPage"
        const val CLOSE_PAGE = "closePage"
        const val LOG = "log"
        const val LOG_AND_TELEMETRY = "logAndTelemetry"
        const val REPORT_DT = "reportDT"
        const val LOCAL_SERVE_TIME = "localServeTime"
        const val SERVER_TIME_MILLIS = "serverTimeMillis"
        const val CURRENT_TIMESTAMP = "currentTimestamp"
        const val DATE_FORMATTER = "dateFormatter"
        const val REPORT_REALTIME = "reportRealTime"
        const val REPORT_PAGE_COST_TIME_FOR_CACHE = "reportPageCostTimeForCache"
        const val REPORT_PAGE_COST_TIME_FOR_SUCCESS = "reportPageCostTimeForSuccess"
        const val REPORT_PAGE_COST_TIME_FOR_ERROR = "reportPageCostTimeForError"
        const val REMOTE_CONFIG = "loadRemoteConfig"
        const val SIGN_ALERT = "signAlert"
        const val CLOSE_KEYBOARD = "closeKeyboard"
        const val URL_ENCODE = "urlEncode"
        const val URL_DECODE = "urlDecode"
        const val HUMAN_VERIFICATION = "humanVerification"
        const val PRELOAD_PB = "preloadPB"
        const val CLEAN_PB = "cleanPB"
        const val KEY_FEED_PB_TOKEN = "feedPbToken"
        const val SET_STATUS_BAR_WHITE = "setWhiteStatusBarStyle"
        const val SET_STATUS_BAR_BLACK = "setBlackStatusBarStyle"
        const val REQUEST_ORIENTATION = "requestOrientation"
        const val GET_CURRENT_ACCOUNT = "getAccount"
        const val DOWNLOAD_PAG_SO = "downloadPagSo"
        const val GET_LOCAL_IMAGE_PATH = "getLocalImagePath"
        const val READ_ASSET_FILE = "readAssetFile"
        const val READ_ASSET_FILE_SYNC = "readAssetFileSync"
    }

}
