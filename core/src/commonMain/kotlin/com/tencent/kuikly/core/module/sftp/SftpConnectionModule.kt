/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License is KuiklyUI;
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
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * SFTP 连接列表 Module（§3.4 / §17.3.1）
 *
 * 持久化用户保存的 [SftpConnection] 连接配置，供 [SftpHomePage] 连接列表使用。
 * 全局单例（§21.7.4），独立于 SFTP session。
 *
 * **删连接联动**（§21.4.5）：
 * - `remove` 时原生侧调 `SftpFavoritesModule.removeByConnection` + `SftpPlaybackHistoryModule.clearByConnection`
 * - 调用方调 [SftpModule.disconnect] 断开当前 session（若有）
 */
class SftpConnectionModule : Module() {
    override fun moduleName(): String = MODULE_NAME

    /** 新增连接，返回 id；label 重复时原生侧追加 `(2)` 后缀 */
    fun add(conn: SftpConnection, callback: (id: String?, error: SftpError?) -> Unit) {
        asyncToNativeMethod(METHOD_ADD, conn.toJson()) { data ->
            val err = data?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(null, SftpError.fromJson(err))
            } else {
                callback(data?.str("id"), null)
            }
        }
    }

    /** 更新连接（id 不变） */
    fun update(conn: SftpConnection, callback: (success: Boolean, error: SftpError?) -> Unit) {
        asyncToNativeMethod(METHOD_UPDATE, conn.toJson()) { data ->
            val err = data?.str("error")
            callback(err.isNullOrEmpty(), if (err.isNullOrEmpty()) null else SftpError.fromJson(err))
        }
    }

    /** 删除连接；原生侧联动清收藏 + 历史 */
    fun remove(id: String, callback: (success: Boolean, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("id", id)
        asyncToNativeMethod(METHOD_REMOVE, params) { data ->
            val err = data?.str("error")
            callback(err.isNullOrEmpty(), if (err.isNullOrEmpty()) null else SftpError.fromJson(err))
        }
    }

    /** 列出所有连接，按 lastUsedAt DESC 排序 */
    fun list(callback: (items: List<SftpConnection>, error: SftpError?) -> Unit) {
        asyncToNativeMethod(METHOD_LIST, JSONObject()) { data ->
            val err = data?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(emptyList(), SftpError.fromJson(err))
            } else {
                val arr = data?.arrOrNull("items") ?: JSONArray()
                callback(SftpConnection.listFromJson(arr), null)
            }
        }
    }

    /** 按 id 获取单个连接 */
    fun get(id: String, callback: (conn: SftpConnection?, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("id", id)
        asyncToNativeMethod(METHOD_GET, params) { data ->
            val err = data?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(null, SftpError.fromJson(err))
            } else {
                callback(data?.objOrNull("conn")?.let { SftpConnection.fromJson(it) }, null)
            }
        }
    }

    /** 更新 lastUsedAt 为当前时间（连接成功后调） */
    fun touchLastUsed(id: String, callback: ((error: SftpError?) -> Unit)? = null) {
        val params = JSONObject()
        params.put("id", id)
        asyncToNativeMethod(METHOD_TOUCH_LAST_USED, params) { _ ->
            callback?.invoke(null)
        }
    }

    companion object {
        const val MODULE_NAME = ModuleConst.SFTP_CONNECTION

        const val METHOD_ADD = "add"
        const val METHOD_UPDATE = "update"
        const val METHOD_REMOVE = "remove"
        const val METHOD_LIST = "list"
        const val METHOD_GET = "get"
        const val METHOD_TOUCH_LAST_USED = "touchLastUsed"
    }
}
