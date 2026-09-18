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

import android.content.Context
import com.tencent.kuikly.core.render.android.css.ktx.toJSONObjectSafely
import com.tencent.kuikly.core.render.android.css.ktx.toMap
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONArray
import org.json.JSONObject

/**
 * SFTP 连接列表 Module（Android 原生侧，§17.3.1 / §21.4.5）
 *
 * - 持久化到 SharedPreferences `sftp_connections`
 * - 删连接联动清收藏 + 历史（§21.4.5）
 * - 密码/密钥字段透传存储；Phase 1.2 接入 EncryptedSharedPreferences 加密
 */
class KRSftpConnectionModule : KuiklyRenderBaseModule() {

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            METHOD_ADD -> add(params, callback)
            METHOD_UPDATE -> update(params, callback)
            METHOD_REMOVE -> remove(params, callback)
            METHOD_LIST -> list(params, callback)
            METHOD_GET -> get(params, callback)
            METHOD_TOUCH_LAST_USED -> touchLastUsed(params, callback)
            else -> super.call(method, params, callback)
        }
    }

    private fun add(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            try {
                val storage = storage()
                val conn = json
                val id = conn.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() }
                conn.put("id", id)
                conn.put("createdAt", System.currentTimeMillis())
                storage.save(conn)
                callback?.invoke(mapOf("id" to id))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun update(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            try {
                val id = json.optString("id")
                if (id.isEmpty()) throw IllegalArgumentException("missing id")
                val storage = storage()
                storage.save(json)  // 覆盖同 id
                callback?.invoke(mapOf("ok" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun remove(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            try {
                val id = json.optString("id")
                val storage = storage()
                storage.remove(id)
                // 联动清收藏 + 历史（§21.4.5）——直接调原生 storage，避免 Module 异步回调
                SftpFavoritesStorage(context).removeByConnection(id)
                SftpPlaybackHistoryStorage(context).clearByConnection(id)
                callback?.invoke(mapOf("ok" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun list(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val storage = storage()
                val items = storage.loadAll()
                // 按 lastUsedAt DESC 排序
                val sorted = items.sortedByDescending { it.optLong("lastUsedAt", 0L) }
                // 裸 JSONArray 过不了桥，必须 JSONObject + toMap()（与 KRSftpModule.list 一致）
                val result = JSONObject()
                val arr = JSONArray()
                sorted.forEach { arr.put(it) }
                result.put("items", arr)
                callback?.invoke(result.toMap())
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun get(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            try {
                val id = json.optString("id")
                val storage = storage()
                val conn = storage.findById(id)
                if (conn != null) {
                    val result = JSONObject()
                    result.put("conn", conn)
                    callback?.invoke(result.toMap())
                } else {
                    callback?.invoke(mapOf("error" to """{"code":3001,"msg":"connection not found"}"""))
                }
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun touchLastUsed(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            try {
                val id = json.optString("id")
                val storage = storage()
                storage.touchLastUsed(id)
                callback?.invoke(mapOf("ok" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun storage(): SftpConnectionStorage = SftpConnectionStorage(context)

    private fun executeOnSubThread(block: () -> Unit) {
        com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
            .krThreadAdapter?.executeOnSubThread(block) ?: Thread(block).start()
    }

    companion object {
        const val MODULE_NAME = "KRSftpConnectionModule"
        const val METHOD_ADD = "add"
        const val METHOD_UPDATE = "update"
        const val METHOD_REMOVE = "remove"
        const val METHOD_LIST = "list"
        const val METHOD_GET = "get"
        const val METHOD_TOUCH_LAST_USED = "touchLastUsed"
    }
}

/** SFTP 连接持久化（SharedPreferences） */
class SftpConnectionStorage(private val context: Context?) {
    private val prefs by lazy {
        context?.getSharedPreferences("sftp_connections", android.content.Context.MODE_PRIVATE)
    }

    fun save(conn: JSONObject) {
        val id = conn.optString("id")
        if (id.isEmpty()) return
        val arr = loadAll().filterNot { it.optString("id") == id }.toMutableList()
        arr.add(conn)
        prefs?.edit()?.putString(KEY, encodeAll(arr))?.apply()
    }

    fun remove(id: String) {
        val arr = loadAll().filterNot { it.optString("id") == id }
        prefs?.edit()?.putString(KEY, encodeAll(arr))?.apply()
    }

    fun findById(id: String): JSONObject? = loadAll().firstOrNull { it.optString("id") == id }

    fun loadAll(): List<JSONObject> {
        val raw = prefs?.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optJSONObject(it) }.filterNotNull()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun touchLastUsed(id: String) {
        val arr = loadAll().toMutableList()
        val idx = arr.indexOfFirst { it.optString("id") == id }
        if (idx < 0) return
        arr[idx].put("lastUsedAt", System.currentTimeMillis())
        prefs?.edit()?.putString(KEY, encodeAll(arr))?.apply()
    }

    private fun encodeAll(list: List<JSONObject>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    companion object {
        private const val KEY = "items"
    }
}

/** KRSftpConnectionModule 全局单例 holder（§21.7.4） */
object KRSftpConnectionModuleHolder {
    val instance: KRSftpConnectionModule by lazy { KRSftpConnectionModule() }
}
