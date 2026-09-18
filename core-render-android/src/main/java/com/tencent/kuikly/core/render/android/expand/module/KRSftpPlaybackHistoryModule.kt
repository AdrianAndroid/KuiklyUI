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

import com.tencent.kuikly.core.render.android.css.ktx.toJSONObjectSafely
import com.tencent.kuikly.core.render.android.css.ktx.toMap
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONArray
import org.json.JSONObject

/**
 * SFTP 播放历史 Module（Android，§20.1 / §21.5）
 *
 * 全局单例，跨 Page 共享；持久化用 `SharedPreferences` 文件 `sftp_playback_history.xml`。
 * 容量 2000 条 LRU（§21.5.1）。
 */
class KRSftpPlaybackHistoryModule : KuiklyRenderBaseModule() {

    // 注意：不能用 by lazy 缓存 —— 模块是全局单例，首次访问时 context 可能尚未注入，
    // 一旦缓存成 prefs 为 null 的实例，之后所有读写都会静默失效（表现为 add 返回 id 但 list 为空）。
    private fun storage(): SftpPlaybackHistoryStorage = SftpPlaybackHistoryStorage(context)

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            METHOD_UPSERT -> upsert(params, callback)
            METHOD_GET -> get(params, callback)
            METHOD_LIST_BY_DIR -> listByDirectory(params, callback)
            METHOD_LIST_BY_CONN -> listByConnection(params, callback)
            METHOD_REMOVE -> remove(params, callback)
            METHOD_CLEAR_BY_CONN -> clearByConnection(params, callback)
            METHOD_MARK_COMPLETED -> markCompleted(params, callback)
            else -> super.call(method, params, callback)
        }
    }

    private fun upsert(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val json = params.toJSONObjectSafely()
                storage().upsert(json)
                callback?.invoke(mapOf("ok" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun get(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val json = params.toJSONObjectSafely()
                val record = storage().get(json.optString("connectionId"), json.optString("remotePath"))
                if (record != null) {
                    callback?.invoke(mapOf("record" to record.toMap()))
                } else {
                    callback?.invoke(mapOf("ok" to true))
                }
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun listByDirectory(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val json = params.toJSONObjectSafely()
                val records = storage().listByDirectory(json.optString("connectionId"), json.optString("directoryPath"))
                val result = JSONObject()
                result.put("records", arrOf(records))
                callback?.invoke(result.toMap())
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun listByConnection(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val json = params.toJSONObjectSafely()
                val records = storage().listByConnection(json.optString("connectionId"))
                val result = JSONObject()
                result.put("records", arrOf(records))
                callback?.invoke(result.toMap())
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun remove(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val json = params.toJSONObjectSafely()
                storage().remove(json.optString("id"))
                callback?.invoke(mapOf("ok" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun clearByConnection(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val json = params.toJSONObjectSafely()
                storage().clearByConnection(json.optString("connectionId"))
                callback?.invoke(mapOf("ok" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun markCompleted(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val json = params.toJSONObjectSafely()
                storage().markCompleted(json.optString("connectionId"), json.optString("remotePath"))
                callback?.invoke(mapOf("ok" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    /** JSONObject/JSONArray 统一转成可过桥的结构（与 KRSftpModule 保持一致） */
    private fun arrOf(items: List<JSONObject>): JSONArray {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        return arr
    }

    private fun executeOnSubThread(block: () -> Unit) {
        com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
            .krThreadAdapter?.executeOnSubThread(block) ?: Thread(block).start()
    }

    companion object {
        const val MODULE_NAME = "KRSftpPlaybackHistoryModule"
        const val METHOD_UPSERT = "upsert"
        const val METHOD_GET = "get"
        const val METHOD_LIST_BY_DIR = "listByDirectory"
        const val METHOD_LIST_BY_CONN = "listByConnection"
        const val METHOD_REMOVE = "remove"
        const val METHOD_CLEAR_BY_CONN = "clearByConnection"
        const val METHOD_MARK_COMPLETED = "markCompleted"
    }
}

/** 全局单例 Holder（§21.7.4） */
object KRSftpPlaybackHistoryModuleHolder {
    val instance: KRSftpPlaybackHistoryModule by lazy { KRSftpPlaybackHistoryModule() }
}
