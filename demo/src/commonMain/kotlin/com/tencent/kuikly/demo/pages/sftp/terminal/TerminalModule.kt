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
package com.tencent.kuikly.demo.pages.sftp.terminal

import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 终端 shell 通道（跨端 Module 契约）。
 *
 * - **Web/桌面**：由 `h5App` 的 `KRTerminalModule` 转发到本地 Node 网关（`sftp-gateway` 的 `shell` 模块，
 *   远程走 ssh2 `conn.shell()`，本地走宿主 pty）。
 * - **Android/iOS/macOS/OHOS**：在各端原生模块中实现同名方法即可（远程用已接入的 libssh2：
 *   `libssh2_channel_open_session` + `libssh2_channel_request_pty`；本地用各自 shell）。
 *   未实现时应由页面先探测（`BridgeModule.supportsTerminal()`）再调用，避免命中「方法不存在」断言。
 *
 * 传输：输出用「绝对偏移 + 拉取」，字节用 base64（与网关一致），HTTP 轮询即可，无需 WebSocket。
 */
internal class TerminalModule : Module() {

    override fun moduleName(): String = MODULE_NAME

    fun open(
        local: Boolean,
        sessionId: String,
        cols: Int,
        rows: Int,
        callback: (shellId: String?, error: String?) -> Unit
    ) {
        val p = JSONObject()
        p.put("local", local)
        if (sessionId.isNotEmpty()) p.put("sessionId", sessionId)
        p.put("cols", cols)
        p.put("rows", rows)
        p.put("term", "xterm-256color")
        asyncToNativeMethod("open", p) { data ->
            val err = data?.optString("error")
            callback(data?.optString("shellId")?.takeIf { it.isNotEmpty() }, if (err.isNullOrEmpty()) null else err)
        }
    }

    fun read(shellId: String, offset: Long, callback: (base64: String, nextOffset: Long, closed: Boolean) -> Unit) {
        val p = JSONObject()
        p.put("shellId", shellId)
        p.put("offset", offset)
        p.put("max", 64 * 1024)
        asyncToNativeMethod("read", p) { data ->
            callback(
                data?.optString("data") ?: "",
                data?.optLong("offset", offset) ?: offset,
                data?.optBoolean("closed", false) ?: false
            )
        }
    }

    fun write(shellId: String, base64Data: String) {
        val p = JSONObject()
        p.put("shellId", shellId)
        p.put("data", base64Data)
        asyncToNativeMethod("write", p, null)
    }

    fun resize(shellId: String, cols: Int, rows: Int) {
        val p = JSONObject()
        p.put("shellId", shellId)
        p.put("cols", cols)
        p.put("rows", rows)
        asyncToNativeMethod("resize", p, null)
    }

    fun close(shellId: String) {
        val p = JSONObject()
        p.put("shellId", shellId)
        asyncToNativeMethod("close", p, null)
    }

    companion object {
        const val MODULE_NAME = "KRTerminalModule"
    }
}
