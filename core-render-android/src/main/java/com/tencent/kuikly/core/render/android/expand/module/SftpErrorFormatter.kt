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

import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.json.JSONObject

/**
 * SFTP 错误格式化器（§21.4.3）
 *
 * 把异常映射为 SftpError JSON 字符串，与 commonMain [com.tencent.kuikly.core.module.sftp.SftpError.fromJson] 兼容。
 */
object SftpErrorFormatter {

    /** 返回 SftpError JSON 字符串，格式：{"code": Int, "msg": String, "detail": String?} */
    fun format(e: Throwable): String {
        val (code, msg) = classify(e)
        val json = JSONObject()
        json.put("code", code)
        json.put("msg", msg)
        e.message?.let { if (it != msg) json.put("detail", it) }
        return json.toString()
    }

    private fun classify(e: Throwable): Pair<Int, String> {
        return when (e) {
            is UnknownHostException -> Pair(1001, "网络不可达")
            is SocketTimeoutException -> Pair(1002, "连接超时")
            is ConnectException -> Pair(1001, "连接被拒绝")
            is JSchException -> {
                val msg = e.message ?: ""
                when {
                    msg.contains("Auth fail") -> Pair(1003, "认证失败")
                    msg.contains("UnknownHostKey") || msg.contains("reject HOSTKEY") -> Pair(1004, "主机指纹不匹配")
                    else -> Pair(1999, msg.ifEmpty { "SSH 错误" })
                }
            }
            is SftpException -> {
                when (e.id) {
                    ChannelSftpErrors.SSH_FX_NO_SUCH_FILE -> Pair(2001, "文件/目录不存在")
                    ChannelSftpErrors.SSH_FX_PERMISSION_DENIED -> Pair(2002, "权限不足")
                    ChannelSftpErrors.SSH_FX_FAILURE -> Pair(2999, e.message ?: "SFTP 操作失败")
                    else -> Pair(2999, e.message ?: "SFTP 错误")
                }
            }
            is IllegalStateException -> Pair(3001, e.message ?: "会话状态错误")
            else -> Pair(0, e.message ?: "未知错误")
        }
    }

    /** SFTP 协议错误码（JSch 中 SftpException.id 的部分） */
    private object ChannelSftpErrors {
        const val SSH_FX_NO_SUCH_FILE = 2
        const val SSH_FX_PERMISSION_DENIED = 3
        const val SSH_FX_FAILURE = 4
    }
}
