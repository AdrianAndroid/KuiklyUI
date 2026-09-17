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

import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderLog
import fi.iki.elonen.NanoHTTPD
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地 HTTP 代理服务器（Android，§5 / §21.3）
 *
 * - 基于 NanoHTTPD；端口 18080-18089 fallback
 * - URL 格式：`http://127.0.0.1:<port>/<token>/<fileName>`
 * - Token TTL 2 小时，每次 read 续期（§21.3.3）
 * - 支持 HTTP Range 请求（§21.3.4）
 * - 通过 [KRSftpClient.read] 流式读 SFTP 文件
 *
 * **Phase 1 简化**：当前用 RandomAccessFile 演示；Phase 1.2 改为通过 sessionId 调用 [KRSftpClient.read]
 */
class LocalHttpProxyServer private constructor(port: Int) : NanoHTTPD(port) {

    private val tokens = ConcurrentHashMap<String, ProxyToken>()
    private val lock = Any()

    init {
        // 启动 server；启动失败在 start() 抛
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri  // e.g. "/<token>/<fileName>"
        val pathParts = uri.trimStart('/').split('/', limit = 2)
        if (pathParts.size < 2) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "invalid url")
        val token = pathParts[0]
        val fileName = pathParts[1]

        val tokenInfo = tokens[token] ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "token expired")
        if (tokenInfo.isExpired()) {
            tokens.remove(token)
            return newFixedLengthResponse(Response.Status.GONE, "text/plain", "token expired")
        }

        // 续期
        tokenInfo.renew()

        // 处理 Range
        val rangeHeader = session.headers["range"]
        val totalSize = tokenInfo.totalSize
        val (start, end) = parseRange(rangeHeader, totalSize)
        val length = end - start + 1

        // Phase 1.2: 这里应该通过 sessionId 调用 KRSftpClient.read
        // 当前简化：直接返回 200 OK 占位
        // 真实实现：
        // val bytes = KRSftpClient.read(tokenInfo.fileHandleId, start, length.toInt())
        // val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, tokenInfo.mime, bytes.inputStream(), length)
        // response.addHeader("Content-Range", "bytes $start-$end/$totalSize")

        // Phase 1 stub：返回空内容
        val response = newFixedLengthResponse(Response.Status.OK, "application/octet-stream", "")
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", length.toString())
        if (rangeHeader != null) {
            response.addHeader("Content-Range", "bytes $start-$end/$totalSize")
        }
        return response
    }

    fun registerToken(sessionId: String, remotePath: String, totalSize: Long): String {
        val token = generateToken()
        tokens[token] = ProxyToken(
            token = token,
            sessionId = sessionId,
            remotePath = remotePath,
            totalSize = totalSize,
            fileHandleId = null,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + TTL_MS
        )
        return token
    }

    fun unregisterToken(token: String) {
        tokens.remove(token)
    }

    private fun generateToken(): String =
        java.security.SecureRandom().let { r ->
            val bytes = ByteArray(16)
            r.nextBytes(bytes)
            bytes.joinToString("") { String.format("%02x", it) }
        }

    private fun parseRange(rangeHeader: String?, totalSize: Long): Pair<Long, Long> {
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) return Pair(0, totalSize - 1)
        val rangeStr = rangeHeader.removePrefix("bytes=").trim()
        if (rangeStr.isEmpty()) return Pair(0, totalSize - 1)
        val dashIdx = rangeStr.indexOf('-')
        val start = rangeStr.substring(0, dashIdx).toLong()
        val endStr = rangeStr.substring(dashIdx + 1)
        val end = if (endStr.isEmpty()) totalSize - 1 else endStr.toLong()
        return Pair(start, end.coerceAtMost(totalSize - 1))
    }

    companion object {
        private const val PORT_MIN = 18080
        private const val PORT_MAX = 18089
        private const val TTL_MS = 2 * 60 * 60 * 1000L  // 2 小时

        @Volatile
        private var instance: LocalHttpProxyServer? = null

        /** 懒启动；端口冲突自动 +1 重试到 PORT_MAX（§21.3.1） */
        fun startOrGet(): LocalHttpProxyServer {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                for (port in PORT_MIN..PORT_MAX) {
                    try {
                        val server = LocalHttpProxyServer(port)
                        server.start(SOCKET_READ_TIMEOUT_DEFAULT, false)
                        KuiklyRenderLog.i(TAG, "LocalHttpProxyServer started on port $port")
                        instance = server
                        return server
                    } catch (e: Exception) {
                        KuiklyRenderLog.w(TAG, "Failed to start on port $port: ${e.message}")
                    }
                }
                throw RuntimeException("All ports $PORT_MIN-$PORT_MAX are in use")
            }
        }
    }
}

private class ProxyToken(
    val token: String,
    val sessionId: String,
    val remotePath: String,
    val totalSize: Long,
    var fileHandleId: String?,
    val createdAt: Long,
    var expiresAt: Long
) {
    fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt
    fun renew() { expiresAt = System.currentTimeMillis() + 2 * 60 * 60 * 1000L }
}

private const val TAG = "LocalHttpProxyServer"
