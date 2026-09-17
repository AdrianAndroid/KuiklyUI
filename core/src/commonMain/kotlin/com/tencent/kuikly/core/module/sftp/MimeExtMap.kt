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
 * 扩展名 → MIME 集中映射表（§21.2.11）
 *
 * 由 [SftpEntry.mimeHint] 在原生侧 `list` 时填充；commonMain 此表用于 UI 图标推断与预览分发。
 * 错误 MIME 会导致 iOS AVPlayer 拒绝播放（§5.4）。
 */
object MimeExtMap {
    private val EXT_TO_MIME: Map<String, String> = mapOf(
        // —— 视频 ——
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mov" to "video/quicktime",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "flv" to "video/x-flv",
        "wmv" to "video/x-ms-wmv",
        "ts" to "video/mp2t",
        "3gp" to "video/3gpp",
        "3g2" to "video/3gpp2",
        "mpeg" to "video/mpeg",
        "mpg" to "video/mpeg",
        "m2ts" to "video/mp2t",
        // —— 音频 ——
        "mp3" to "audio/mpeg",
        "aac" to "audio/aac",
        "m4a" to "audio/mp4",
        "flac" to "audio/flac",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/opus",
        "wma" to "audio/x-ms-wma",
        "aiff" to "audio/aiff",
        "alac" to "audio/alac",
        // —— 图片 ——
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "ico" to "image/x-icon",
        "tiff" to "image/tiff",
        "tif" to "image/tiff",
        "heic" to "image/heic",
        // —— 文本/代码 ——
        "txt" to "text/plain",
        "log" to "text/plain",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "json" to "application/json",
        "xml" to "application/xml",
        "yaml" to "text/yaml",
        "yml" to "text/yaml",
        "toml" to "text/plain",
        "csv" to "text/csv",
        "tsv" to "text/tab-separated-values",
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "js" to "application/javascript",
        "mjs" to "application/javascript",
        "ts" to "application/typescript",
        "kt" to "text/x-kotlin",
        "kts" to "text/x-kotlin",
        "java" to "text/x-java-source",
        "c" to "text/x-c",
        "h" to "text/x-c",
        "cpp" to "text/x-c++",
        "cxx" to "text/x-c++",
        "cc" to "text/x-c++",
        "hpp" to "text/x-c++",
        "hxx" to "text/x-c++",
        "py" to "text/x-python",
        "rb" to "text/x-ruby",
        "go" to "text/x-go",
        "rs" to "text/x-rust",
        "swift" to "text/x-swift",
        "m" to "text/x-objc",
        "mm" to "text/x-objc++",
        "sh" to "application/x-sh",
        "bash" to "application/x-sh",
        "zsh" to "application/x-sh",
        "sql" to "application/sql",
        // —— 文档 ——
        "pdf" to "application/pdf",
        // —— 归档（不预览） ——
        "zip" to "application/zip",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "bz2" to "application/x-bzip2",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/x-rar-compressed",
        "iso" to "application/x-iso9660-image"
    )

    /** 按扩展名推断 MIME；未知返回 `application/octet-stream` */
    fun mimeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return EXT_TO_MIME[ext] ?: "application/octet-stream"
    }

    /** 按扩展名推断 MIME（按路径） */
    fun mimeOfPath(path: String): String = mimeOf(path.substringAfterLast('/'))

    /** 是否是视频 */
    fun isVideo(mime: String?): Boolean = mime != null && mime.startsWith("video/")

    /** 是否是音频 */
    fun isAudio(mime: String?): Boolean = mime != null && mime.startsWith("audio/")

    /** 是否是图片 */
    fun isImage(mime: String?): Boolean = mime != null && mime.startsWith("image/")

    /** 是否是文本/代码（可文本预览） */
    fun isText(mime: String?): Boolean {
        if (mime == null) return false
        return mime.startsWith("text/") ||
            mime in setOf("application/json", "application/javascript", "application/typescript", "application/xml", "application/sql", "application/x-sh")
    }

    /** 是否是 Markdown */
    fun isMarkdown(mime: String?): Boolean = mime == "text/markdown"

    /** 是否是 HTML */
    fun isHtml(mime: String?): Boolean = mime == "text/html"

    /** 是否是 PDF */
    fun isPdf(mime: String?): Boolean = mime == "application/pdf"

    /** 是否是 SVG（特殊处理，图片但需矢量渲染） */
    fun isSvg(mime: String?): Boolean = mime == "image/svg+xml"

    /** 是否是归档文件（不预览） */
    fun isArchive(mime: String?): Boolean = mime != null && mime.startsWith("application/") && (
        mime.contains("zip") || mime.contains("tar") || mime.contains("gzip") || mime.contains("bzip") || mime.contains("7z") || mime.contains("rar") || mime.contains("iso")
    )
}
