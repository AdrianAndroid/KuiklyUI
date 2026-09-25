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
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * SFTP 播放历史 Module（§20.1 / §21.5）
 *
 * 与 [SftpFavoritesModule] 同构，全局单例（§21.7.4）。容量 2000 条 LRU。
 */
class SftpPlaybackHistoryModule : Module() {
    override fun moduleName(): String = MODULE_NAME

    fun upsert(record: SftpPlaybackRecord, callback: (success: Boolean, error: SftpError?) -> Unit) {
        asyncToNativeMethod(METHOD_UPSERT, record.toJson()) { data ->
            val err = data?.str("error")
            callback(err.isNullOrEmpty(), if (err.isNullOrEmpty()) null else SftpError.fromJson(err))
        }
    }

    fun get(connectionId: String, remotePath: String, callback: (record: SftpPlaybackRecord?, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("connectionId", connectionId)
        params.put("remotePath", remotePath)
        asyncToNativeMethod(METHOD_GET, params) { data ->
            val err = data?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(null, SftpError.fromJson(err))
            } else {
                callback(data?.objOrNull("record")?.let { SftpPlaybackRecord.fromJson(it) }, null)
            }
        }
    }

    fun listByDirectory(connectionId: String, directoryPath: String, callback: (records: List<SftpPlaybackRecord>, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("connectionId", connectionId)
        params.put("directoryPath", directoryPath)
        asyncToNativeMethod(METHOD_LIST_BY_DIR, params) { data ->
            val err = data?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(emptyList(), SftpError.fromJson(err))
            } else {
                val arr = data?.arrOrNull("records") ?: JSONArray()
                callback(SftpPlaybackRecord.listFromJson(arr), null)
            }
        }
    }

    fun listByConnection(connectionId: String, callback: (records: List<SftpPlaybackRecord>, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("connectionId", connectionId)
        asyncToNativeMethod(METHOD_LIST_BY_CONN, params) { data ->
            val err = data?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(emptyList(), SftpError.fromJson(err))
            } else {
                val arr = data?.arrOrNull("records") ?: JSONArray()
                callback(SftpPlaybackRecord.listFromJson(arr), null)
            }
        }
    }

    /** 设置播放历史容量（追加语义：超限丢最旧；默认 1000）。Web 端由网关持久化。 */
    fun setLimit(limit: Int, callback: ((limit: Int) -> Unit)? = null) {
        val params = JSONObject()
        params.put("limit", limit)
        asyncToNativeMethod("setLimit", params) { data ->
            callback?.invoke(data?.optInt("limit", limit) ?: limit)
        }
    }

    fun remove(id: String, callback: (success: Boolean, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("id", id)
        asyncToNativeMethod(METHOD_REMOVE, params) { data ->
            val err = data?.str("error")
            callback(err.isNullOrEmpty(), if (err.isNullOrEmpty()) null else SftpError.fromJson(err))
        }
    }

    fun clearByConnection(connectionId: String, callback: (success: Boolean, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("connectionId", connectionId)
        asyncToNativeMethod(METHOD_CLEAR_BY_CONN, params) { data ->
            val err = data?.str("error")
            callback(err.isNullOrEmpty(), if (err.isNullOrEmpty()) null else SftpError.fromJson(err))
        }
    }

    fun markCompleted(connectionId: String, remotePath: String, callback: (success: Boolean, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("connectionId", connectionId)
        params.put("remotePath", remotePath)
        asyncToNativeMethod(METHOD_MARK_COMPLETED, params) { data ->
            val err = data?.str("error")
            callback(err.isNullOrEmpty(), if (err.isNullOrEmpty()) null else SftpError.fromJson(err))
        }
    }

    companion object {
        const val MODULE_NAME = ModuleConst.SFTP_PLAYBACK_HISTORY

        const val METHOD_UPSERT = "upsert"
        const val METHOD_GET = "get"
        const val METHOD_LIST_BY_DIR = "listByDirectory"
        const val METHOD_LIST_BY_CONN = "listByConnection"
        const val METHOD_REMOVE = "remove"
        const val METHOD_CLEAR_BY_CONN = "clearByConnection"
        const val METHOD_MARK_COMPLETED = "markCompleted"
    }
}
