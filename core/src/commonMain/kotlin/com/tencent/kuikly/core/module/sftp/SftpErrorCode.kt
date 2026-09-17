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

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 统一 SFTP 错误码（§21.7.1）
 *
 * 分段：1xxx 连接 / 2xxx 文件操作 / 3xxx 协议 / 4xxx 预览 / 5xxx 代理 / 9xxx 通用
 * `httpStatus` 仅对代理返回时生效，0 表示无对应 HTTP 状态。
 */
enum class SftpErrorCode(val code: Int, val httpStatus: Int = 0) {
    // —— 连接类（1001-1099） ——
    NETWORK_UNREACHABLE(1001),
    CONNECTION_TIMEOUT(1002),
    AUTH_FAILED(1003),
    HOST_KEY_MISMATCH(1004),
    HOST_KEY_REJECTED(1005),
    SERVER_REFUSED(1006),
    CONNECTION_RESET(1007),
    IDLE_DISCONNECTED(1008),
    RECONNECT_FAILED(1009),

    // —— 文件操作类（2001-2099） ——
    PERMISSION_DENIED(2001),
    OPERATION_NOT_PERMITTED(2002),
    NO_SUCH_FILE(2003),
    NO_SUCH_PATH(2004),
    DIR_NOT_EMPTY(2005),
    DISK_FULL(2006),
    FILE_EXISTS(2007),
    SYMLINK_LOOP(2008),
    READ_ONLY_FS(2009),
    PATH_TOO_LONG(2010),
    NAME_TOO_LONG(2011),

    // —— 协议类（3001-3099） ——
    PROTOCOL_ERROR(3001),
    UNSUPPORTED_OPCODE(3002),
    PACKET_CORRUPTED(3003),

    // —— 预览类（4001-4099） ——
    PDF_PASSWORD_REQUIRED(4001),
    PREVIEW_TOO_LARGE(4002),
    BINARY_NOT_PREVIEWABLE(4003),
    PREVIEW_TIMEOUT(4004),
    PREVIEW_CONCURRENCY_LIMIT(4005),

    // —— 代理类（5001-5099） ——
    PROXY_START_FAILED(5001),
    PROXY_PORT_CONFLICT(5002),
    TOKEN_EXPIRED(5003, httpStatus = 410),
    PROXY_READ_FAILED(5004, httpStatus = 502),
    PROXY_SEEK_FAILED(5005, httpStatus = 502),

    // —— 通用类（9001-9999） ——
    CANCELLED(9001),
    TIMEOUT(9002),
    NOT_IMPLEMENTED(9999),
    UNKNOWN(0);

    companion object {
        fun fromCode(code: Int): SftpErrorCode = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * 连接阶段错误分类（§21.1.5）
 *
 * 用于 UI 分支提示：是网络问题、凭据问题、指纹问题、还是服务端拒绝。
 */
enum class SftpConnectError(val code: Int) {
    NETWORK_UNREACHABLE(1001),
    CONNECTION_TIMEOUT(1002),
    AUTH_FAILED(1003),
    HOST_KEY_MISMATCH(1004),
    HOST_KEY_REJECTED(1005),
    SERVER_REFUSED(1006),
    UNKNOWN(0);

    companion object {
        fun fromCode(code: Int): SftpConnectError =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * 错误回包结构（§3.2 / §21.7.1）
 *
 * Kotlin 签名中 `error: String?` 实际是 JSON 字符串，内容为本对象。
 * `code` 取自 [SftpErrorCode]，`msg` 为 i18n key，`detail` 为调试信息不展示给用户。
 */
data class SftpError(
    val code: Int,
    val msg: String,
    val detail: String? = null
) {
    val errorCode: SftpErrorCode get() = SftpErrorCode.fromCode(code)
    val connectError: SftpConnectError? get() = SftpConnectError.fromCode(code).takeIf { it != SftpConnectError.UNKNOWN }

    fun toJsonString(): String {
        val json = JSONObject()
        json.put("code", code)
        json.put("msg", msg)
        detail?.let { json.put("detail", it) }
        return json.toString()
    }

    companion object {
        /**
         * 解析原生侧回包的 error 字段（可能是 JSON 字符串，也可能是裸文本）。
         * 兼容旧版本原生侧只回纯文本消息的情况：fallback 成 `UNKNOWN` code + 原文本做 msg。
         */
        fun fromJson(error: String?): SftpError? {
            if (error.isNullOrEmpty()) return null
            return try {
                val json = JSONObject(error)
                SftpError(
                    code = if (json.has("code")) json.optInt("code", 0) else 0,
                    msg = if (json.has("msg")) json.optString("msg") else error,
                    detail = if (json.has("detail")) json.optString("detail") else null
                )
            } catch (e: Exception) {
                // 兼容旧版原生侧纯文本
                SftpError(code = SftpErrorCode.UNKNOWN.code, msg = error)
            }
        }

        /** 从 code 直接构造（msg 用 i18n key，detail 可空） */
        fun of(code: SftpErrorCode, detail: String? = null): SftpError =
            SftpError(code = code.code, msg = "sftp.error.${code.name.lowercase()}", detail = detail)
    }
}
