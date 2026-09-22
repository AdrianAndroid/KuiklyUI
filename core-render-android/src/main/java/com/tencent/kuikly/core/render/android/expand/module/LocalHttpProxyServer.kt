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
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地 HTTP 代理服务器（Android，§5 / §21.3）
 *
 * - 基于 NanoHTTPD；端口 18080-18089 fallback
 * - URL 格式：`http://127.0.0.1:<port>/<token>/<fileName>`
 * - Token TTL 2 小时，每次 read 续期（§21.3.3）
 * - 支持 HTTP Range 请求（§21.3.4）：把播放器的 Range 转成 SFTP 的随机读
 *   （[KRSftpClient.openRead] 懒打开 + [KRSftpClient.read] 按偏移读）
 *
 * 与 macOS/iOS 端语义保持一致：
 * - 只回「客户端请求的区间」，单次不超过 [MAX_CHUNK_BYTES]，播放器会继续请求后续区间；
 * - 首位字节无需读完整个文件即可下发（避免高首字节延迟导致播放器反复重发同一 Range）；
 * - 未实现/打开失败一律显式返回错误码，**绝不伪报成功**（§13.3）。
 * - **安全：只绑定回环 127.0.0.1**。`NanoHTTPD(port)` 会绑到 0.0.0.0（全网卡），
 *   把远端文件内容通过明文 HTTP 暴露到局域网；token 泄露即文件泄露。
 */
class LocalHttpProxyServer private constructor(port: Int) : NanoHTTPD("127.0.0.1", port) {

    private val tokens = ConcurrentHashMap<String, ProxyToken>()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri  // e.g. "/<token>/<fileName>"
        val pathParts = uri.trimStart('/').split('/', limit = 2)
        if (pathParts.size < 2) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "invalid url")
        }
        val token = pathParts[0]

        val tokenInfo = tokens[token]
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "token not found")
        if (tokenInfo.isExpired()) {
            releaseToken(token, tokenInfo)
            return newFixedLengthResponse(Response.Status.GONE, "text/plain", "token expired")
        }
        tokenInfo.renew()

        // 懒打开远端读句柄：注册 token 时不占资源，首次请求才真正 open
        val handleId = try {
            tokenInfo.fileHandleId ?: KRSftpClient
                .openRead(tokenInfo.sessionId, tokenInfo.remotePath)
                .also { tokenInfo.fileHandleId = it }
        } catch (e: Exception) {
            KuiklyRenderLog.e(TAG, "open remote failed: ${tokenInfo.remotePath}, ${e.message}")
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "open failed: ${e.message}"
            )
        }

        val totalSize = tokenInfo.totalSize
        val rangeHeader = session.headers["range"]
        val range = parseRange(rangeHeader, totalSize)
        if (range == null) {
            // 起点越界（如播放器的 bytes=<size>- 探测）：按 HTTP 语义返回 416
            val resp = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
            resp.addHeader("Content-Range", "bytes */$totalSize")
            return resp
        }

        val start = range.first
        // 单次响应上限：避免为了回一个 Range 读入过多数据；播放器会请求后续区间
        val end = minOf(range.second, start + MAX_CHUNK_BYTES - 1)
        val length = (end - start + 1).toInt()

        val bytes = try {
            KRSftpClient.read(handleId, start, length)
        } catch (e: Exception) {
            KuiklyRenderLog.e(TAG, "read failed: offset=$start len=$length, ${e.message}")
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "read failed: ${e.message}"
            )
        }

        val status = if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            Response.Status.PARTIAL_CONTENT
        } else {
            Response.Status.OK
        }
        val response = newFixedLengthResponse(
            status, mimeOf(tokenInfo.remotePath), bytes.inputStream(), bytes.size.toLong()
        )
        response.addHeader("Accept-Ranges", "bytes")
        if (status == Response.Status.PARTIAL_CONTENT) {
            response.addHeader("Content-Range", "bytes $start-$end/$totalSize")
        }
        response.addHeader("Cache-Control", "no-store")
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
        tokens.remove(token)?.let { releaseToken(token, it) }
    }

    /** 释放 token 持有的远端读句柄，避免泄漏 SFTP channel */
    private fun releaseToken(token: String, info: ProxyToken) {
        tokens.remove(token)
        info.fileHandleId?.let { handleId ->
            runCatching { KRSftpClient.close(handleId) }
                .onFailure { KuiklyRenderLog.e(TAG, "close handle failed: ${it.message}") }
        }
        info.fileHandleId = null
    }

    private fun generateToken(): String =
        java.security.SecureRandom().let { r ->
            val bytes = ByteArray(16)
            r.nextBytes(bytes)
            bytes.joinToString("") { String.format("%02x", it) }
        }

    /**
     * 解析 `bytes=start-end` / `bytes=start-` / `bytes=-suffix`。
     * 返回 null 表示起点越界（不可满足，应回 416）。
     */
    private fun parseRange(rangeHeader: String?, totalSize: Long): Pair<Long, Long>? {
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=") || totalSize <= 0) {
            return Pair(0, (totalSize - 1).coerceAtLeast(0))
        }
        val spec = rangeHeader.removePrefix("bytes=").trim()
        if (spec.contains(',')) return Pair(0, totalSize - 1)   // 多段不支持，退化为整段
        val dashIdx = spec.indexOf('-')
        if (dashIdx < 0) return Pair(0, totalSize - 1)

        val startStr = spec.substring(0, dashIdx)
        val endStr = spec.substring(dashIdx + 1)

        if (startStr.isEmpty()) {
            // 后缀 Range：最后 N 字节
            val suffix = endStr.toLongOrNull() ?: return Pair(0, totalSize - 1)
            if (suffix <= 0) return null
            val start = (totalSize - suffix).coerceAtLeast(0)
            return Pair(start, totalSize - 1)
        }

        val start = startStr.toLongOrNull() ?: return Pair(0, totalSize - 1)
        if (start >= totalSize) return null          // 起点越界 → 416
        val end = if (endStr.isEmpty()) totalSize - 1
                  else (endStr.toLongOrNull() ?: (totalSize - 1)).coerceAtMost(totalSize - 1)
        if (end < start) return null
        return Pair(start, end)
    }

    /** 按扩展名给出 MIME，便于播放器/预览器识别容器格式 */
    private fun mimeOf(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "ts" -> "video/mp2t"
            "flv" -> "video/x-flv"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain; charset=utf-8"
            "md" -> "text/markdown; charset=utf-8"
            "html" -> "text/html; charset=utf-8"
            "json" -> "application/json"
            "xml" -> "application/xml"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val PORT_MIN = 18080
        private const val PORT_MAX = 18089
        private const val TTL_MS = 2 * 60 * 60 * 1000L        // 2 小时
        private const val MAX_CHUNK_BYTES = 4 * 1024 * 1024L  // 单次响应上限 4MB
        private const val SOCKET_READ_TIMEOUT_MS = 5000       // NanoHTTPD socket 读超时

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
                        server.start(SOCKET_READ_TIMEOUT_MS, false)
                        KuiklyRenderLog.i(TAG, "LocalHttpProxyServer started on port $port")
                        instance = server
                        return server
                    } catch (e: Exception) {
                        KuiklyRenderLog.e(TAG, "Failed to start on port $port: ${e.message}")
                    }
                }
                throw RuntimeException("All ports $PORT_MIN-$PORT_MAX are in use")
            }
        }

        /** App 退出/业务结束时关闭代理并释放所有远端句柄 */
        fun stopServer() {
            synchronized(this) {
                instance?.let { server ->
                    runCatching {
                        server.tokens.keys.toList().forEach { token ->
                            server.tokens[token]?.let { server.releaseToken(token, it) }
                        }
                        server.stop()
                    }
                    instance = null
                }
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
