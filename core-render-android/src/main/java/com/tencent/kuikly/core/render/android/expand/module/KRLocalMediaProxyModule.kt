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

import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONObject

/**
 * 本地媒体代理 Module（Android，§5 / §7.3）
 *
 * 与 iOS/macOS 的 `KRLocalMediaProxyModule` 同名同语义：Kotlin 侧统一通过
 * `SftpMediaProxyModule`（moduleName = `KRLocalMediaProxyModule`）调用，
 * 由原生侧启动本地 HTTP 服务并把播放器的 Range 请求转成 SFTP 随机读，
 * 从而无需先下载整个视频即可边下边播。
 */
class KRLocalMediaProxyModule : KuiklyRenderBaseModule() {

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            METHOD_START_OR_GET_PORT -> startOrGetPort(callback)
            METHOD_REGISTER_TOKEN -> registerToken(params, callback)
            METHOD_UNREGISTER_TOKEN -> unregisterToken(params, callback)
            METHOD_STOP -> stop(callback)
            else -> super.call(method, params, callback)
        }
    }

    private fun startOrGetPort(callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val port = LocalHttpProxyServer.startOrGet().listeningPort
                callback?.invoke(mapOf("port" to port))
            } catch (e: Exception) {
                // 启动失败必须显式告知（返回 0 = 不可用），不要伪报成功
                callback?.invoke(mapOf("port" to 0, "error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun registerToken(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJsonObjectSafely()
            val sessionId = json?.optString("sessionId").orEmpty()
            val remotePath = json?.optString("remotePath").orEmpty()
            val totalSize = json?.optLong("totalSize") ?: 0L
            if (sessionId.isEmpty() || remotePath.isEmpty()) {
                callback?.invoke(
                    mapOf("token" to "", "error" to SftpErrorFormatter.format(
                        IllegalArgumentException("sessionId/remotePath required")
                    ))
                )
                return@executeOnSubThread
            }
            try {
                val token = LocalHttpProxyServer.startOrGet()
                    .registerToken(sessionId, remotePath, totalSize)
                callback?.invoke(mapOf("token" to token))
            } catch (e: Exception) {
                callback?.invoke(mapOf("token" to "", "error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun unregisterToken(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val token = params.toJsonObjectSafely()?.optString("token").orEmpty()
            if (token.isNotEmpty()) {
                runCatching { LocalHttpProxyServer.startOrGet().unregisterToken(token) }
            }
            callback?.invoke(mapOf("ok" to true))
        }
    }

    private fun stop(callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            runCatching { LocalHttpProxyServer.stopServer() }
            callback?.invoke(mapOf("ok" to true))
        }
    }

    private fun executeOnSubThread(block: () -> Unit) {
        com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
            .krThreadAdapter?.executeOnSubThread(block) ?: Thread(block).start()
    }

    private fun String?.toJsonObjectSafely(): JSONObject? =
        if (isNullOrEmpty()) null else runCatching { JSONObject(this) }.getOrNull()

    companion object {
        const val MODULE_NAME = "KRLocalMediaProxyModule"

        private const val METHOD_START_OR_GET_PORT = "startOrGetPort"
        private const val METHOD_REGISTER_TOKEN = "registerToken"
        private const val METHOD_UNREGISTER_TOKEN = "unregisterToken"
        private const val METHOD_STOP = "stop"
    }
}
