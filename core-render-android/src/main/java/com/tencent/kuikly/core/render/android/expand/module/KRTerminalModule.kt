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
package com.tencent.kuikly.core.render.android.expand.module

import android.util.Base64
import com.jcraft.jsch.ChannelShell
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONObject
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 终端 shell 通道（Android，JSch `ChannelShell` + pty）。
 *
 * - 复用 `KRSftpClient` 已认证的 SFTP 会话（remote 终端传 sessionId）→ `shell` channel
 * - 输出用「绝对偏移 + 拉取」（与 Web 网关一致），字节 base64；后台线程把 channel 输出泵入内存缓冲
 * - **本地终端**（local=true）在 Android 无本地 shell → 显式返回错误（绝不伪报成功）
 *
 * 契约与 `demo/.../terminal/TerminalModule.kt` 一致：open/read/write/resize/close。
 */
class KRTerminalModule : KuiklyRenderBaseModule() {

    private class Shell(
        val channel: ChannelShell,
        val output: OutputStream,
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream(),
        val lock: Any = Any(),
    )

    private val shells = ConcurrentHashMap<String, Shell>()
    private val counter = AtomicLong(0)

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            "open" -> open(params, callback)
            "read" -> read(params, callback)
            "write" -> write(params)
            "resize" -> resize(params)
            "close" -> close(params)
            else -> super.call(method, params, callback)
        }
    }

    private fun open(params: String?, callback: KuiklyRenderCallback?) {
        val o = JSONObject(params ?: "{}")
        val local = o.optBoolean("local", false)
        if (local) {
            // Android 无本地 shell（本地终端仅 Web/桌面）→ 显式失败，不伪报
            callback?.invoke(mapOf("error" to "not implemented: local shell"))
            return
        }
        val sessionId = o.optString("sessionId")
        val cols = o.optInt("cols", 80)
        val rows = o.optInt("rows", 24)
        val channel = try {
            KRSftpClient.openShellChannel(sessionId, cols, rows)
        } catch (e: Exception) {
            null
        }
        if (channel == null) {
            callback?.invoke(mapOf("error" to "invalid sessionId"))
            return
        }
        val shell = Shell(channel, channel.outputStream)
        val id = "term-${counter.incrementAndGet()}"
        shells[id] = shell
        // 后台泵：把 channel 输出读入内存缓冲（offset 拉取）
        Thread {
            val buf = ByteArray(4096)
            try {
                val input = channel.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(shell.lock) { shell.buffer.write(buf, 0, n) }
                }
            } catch (e: Exception) {
                // channel 关闭/异常 → 退出泵线程（closed 由 read 的 isConnected 反映）
            }
        }.start()
        callback?.invoke(mapOf("shellId" to id))
    }

    private fun read(params: String?, callback: KuiklyRenderCallback?) {
        val o = JSONObject(params ?: "{}")
        val id = o.optString("shellId")
        val from = o.optLong("offset", 0L)
        val shell = shells[id]
        if (shell == null) {
            callback?.invoke(mapOf("data" to "", "offset" to from, "closed" to true))
            return
        }
        var data = ByteArray(0)
        synchronized(shell.lock) {
            val all = shell.buffer.toByteArray()
            if (from < all.size) {
                data = all.copyOfRange(from.toInt(), all.size)
            }
        }
        val next = from + data.size
        callback?.invoke(
            mapOf(
                "data" to Base64.encodeToString(data, Base64.NO_WRAP),
                "offset" to next,
                "closed" to !shell.channel.isConnected,
            )
        )
    }

    private fun write(params: String?) {
        val o = JSONObject(params ?: "{}")
        val shell = shells[o.optString("shellId")] ?: return
        val bytes = try {
            Base64.decode(o.optString("data"), Base64.DEFAULT)
        } catch (e: Exception) {
            ByteArray(0)
        }
        if (bytes.isEmpty()) return
        try {
            shell.output.write(bytes)
            shell.output.flush()
        } catch (e: Exception) {
            // 写入失败（channel 已关）→ 忽略，read 端会报 closed
        }
    }

    private fun resize(params: String?) {
        val o = JSONObject(params ?: "{}")
        val shell = shells[o.optString("shellId")] ?: return
        try {
            shell.channel.setPtySize(
                o.optInt("cols", 80).coerceIn(20, 400),
                o.optInt("rows", 24).coerceIn(5, 200),
                0, 0
            )
        } catch (e: Exception) {
        }
    }

    private fun close(params: String?) {
        val o = JSONObject(params ?: "{}")
        val shell = shells.remove(o.optString("shellId")) ?: return
        try {
            shell.channel.disconnect()
        } catch (e: Exception) {
        }
    }

    companion object {
        const val MODULE_NAME = "KRTerminalModule"
    }
}
