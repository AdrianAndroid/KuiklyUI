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
package com.tencent.kuikly.core.module.sftp

/**
 * SFTP 媒体 URL 构建器（§5.2 / §5.3）
 *
 * 播放器通过 `http://127.0.0.1:<port>/<token>/<fileName>` 访问本地代理；
 * 代理将 HTTP Range 请求转换为 SFTP `lseek + read`。
 *
 * Token 出现在 URL path 中，但日志输出时需脱敏（§21.3.7 / §9 第 8 条）。
 */
object SftpMediaUrlBuilder {

    /** 构建播放 URL */
    fun buildPlayUrl(port: Int, token: String, fileName: String): String {
        val safeName = fileName.substringAfterLast('/').ifEmpty { "file" }
        return "http://127.0.0.1:$port/$token/$safeName"
    }

    /**
     * 解析 Range 请求头，返回 [start, endInclusive]（字节偏移）。
     * 仅支持 `bytes=start-end` 格式；`bytes=start-` 表示到文件末尾。
     * 不支持多段 Range（`bytes=a-b,c-d`），返回 null。
     *
     * @param rangeHeader "Range" 头值
     * @param totalSize 文件总大小
     * @return Pair(start, endInclusive) 或 null 表示无效或"全部"
     */
    fun parseRange(rangeHeader: String?, totalSize: Long): Pair<Long, Long>? {
        if (rangeHeader.isNullOrEmpty() || !rangeHeader.startsWith("bytes=")) return null
        val spec = rangeHeader.removePrefix("bytes=").trim()
        if (spec.contains(",")) return null   // 多段 Range 不支持
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startStr = spec.substring(0, dash).trim()
        val endStr = spec.substring(dash + 1).trim()
        val start = startStr.toLongOrNull() ?: return null
        val end = if (endStr.isEmpty()) totalSize - 1 else endStr.toLongOrNull() ?: return null
        if (start < 0 || end < start || end >= totalSize) return null
        return Pair(start, end)
    }

    /**
     * 构建 Content-Range 响应头值
     * @param start 起始字节
     * @param end 结束字节（含）
     * @param total 总大小
     */
    fun buildContentRange(start: Long, end: Long, total: Long): String = "bytes $start-$end/$total"

    /** 日志脱敏：token 只打前 8 位 + `***` */
    fun safeTokenForLog(token: String): String =
        if (token.length <= 8) "${token}***" else "${token.substring(0, 8)}***"

    /** 日志脱敏：URL 中的 token 替换为前 8 位 + `***` */
    fun safeUrlForLog(url: String): String {
        // http://127.0.0.1:port/<token>/file → 替换 path 第一段
        val marker = "127.0.0.1:"
        val portStart = url.indexOf(marker)
        if (portStart < 0) return url
        val pathStart = url.indexOf('/', portStart + marker.length)
        if (pathStart < 0) return url
        val afterPath = url.indexOf('/', pathStart + 1)
        val end = if (afterPath < 0) url.length else afterPath
        val token = url.substring(pathStart + 1, end)
        return url.substring(0, pathStart + 1) + safeTokenForLog(token) + url.substring(end)
    }
}
