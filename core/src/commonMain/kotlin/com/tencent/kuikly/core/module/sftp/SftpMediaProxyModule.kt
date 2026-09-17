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

import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.module.ModuleConst
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 本地媒体代理 Module（§5 / §7.3）
 *
 * 原生侧启动一个本地 HTTP 服务，把播放器的 HTTP Range 请求转换为 SFTP 的
 * `lseek + read`（§5.2），因此不需要先把整个视频下载到本地即可边下边播。
 *
 * 播放地址形如 `http://127.0.0.1:<port>/<token>/<fileName>`，由
 * [SftpMediaUrlBuilder.buildPlayUrl] 拼装。
 */
class SftpMediaProxyModule : Module() {
    override fun moduleName(): String = MODULE_NAME

    /** 启动（幂等）并返回本地代理端口；失败返回 0 */
    fun startOrGetPort(callback: (port: Int) -> Unit) {
        asyncToNativeMethod(METHOD_START_OR_GET_PORT, JSONObject()) { data ->
            callback(data?.optInt("port", 0) ?: 0)
        }
    }

    /** 注册播放 token（TTL 2 小时，每次读取续期）；失败回调空串 */
    fun registerToken(sessionId: String, remotePath: String, totalSize: Long, callback: (token: String) -> Unit) {
        val params = JSONObject()
        params.put("sessionId", sessionId)
        params.put("remotePath", remotePath)
        params.put("totalSize", totalSize)
        asyncToNativeMethod(METHOD_REGISTER_TOKEN, params) { data ->
            callback(data?.optString("token", "") ?: "")
        }
    }

    /** 注销 token，释放远端 fileHandle（§21.3.2） */
    fun unregisterToken(token: String, callback: (() -> Unit)? = null) {
        val params = JSONObject()
        params.put("token", token)
        asyncToNativeMethod(METHOD_UNREGISTER_TOKEN, params) { _ ->
            callback?.invoke()
        }
    }

    /** App 退出时关闭代理 */
    fun stop(callback: (() -> Unit)? = null) {
        asyncToNativeMethod(METHOD_STOP, JSONObject()) { _ ->
            callback?.invoke()
        }
    }

    companion object {
        const val MODULE_NAME = ModuleConst.SFTP_MEDIA_PROXY

        private const val METHOD_START_OR_GET_PORT = "startOrGetPort"
        private const val METHOD_REGISTER_TOKEN = "registerToken"
        private const val METHOD_UNREGISTER_TOKEN = "unregisterToken"
        private const val METHOD_STOP = "stop"
    }
}
