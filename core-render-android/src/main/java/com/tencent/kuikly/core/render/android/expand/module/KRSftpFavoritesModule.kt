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
 * SFTP 收藏 Module（Android，§3.4 / §21.4）
 *
 * - **全局单例**：通过 [KRSftpFavoritesModuleHolder] 持有，跨 Page 共享
 * - 持久化：`SharedPreferences` 文件 `sftp_favorites.xml`
 * - 容量：无上限（用户可手动清理）
 *
 * 注册方式（§21.7.4）：在 `KuiklyRenderViewBaseDelegator.registerModule` 注册为全局单例，
 * 不通过 `createExternalModules` 创建。
 */
class KRSftpFavoritesModule : KuiklyRenderBaseModule() {

    // 注意：不能用 by lazy 缓存 —— 模块是全局单例，首次访问时 context 可能尚未注入，
    // 一旦缓存成 prefs 为 null 的实例，之后所有读写都会静默失效（表现为 add 返回 id 但 list 为空）。
    private fun storage(): SftpFavoritesStorage = SftpFavoritesStorage(context)

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            METHOD_ADD -> add(params, callback)
            METHOD_REMOVE -> remove(params, callback)
            METHOD_REMOVE_BY_CONNECTION -> removeByConnection(params, callback)
            METHOD_LIST -> list(params, callback)
            METHOD_IS_FAVORITED -> isFavorited(params, callback)
            METHOD_UPDATE -> update(params, callback)
            METHOD_SEARCH -> search(params, callback)
            else -> super.call(method, params, callback)
        }
    }

    private fun add(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val json = params.toJSONObjectSafely()
                val id = storage().add(json)
                callback?.invoke(mapOf("id" to id))
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

    private fun removeByConnection(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val json = params.toJSONObjectSafely()
                val removedCount = storage().removeByConnection(json.optString("connectionId"))
                callback?.invoke(mapOf("ok" to true, "removedCount" to removedCount))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun list(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val json = params.toJSONObjectSafely()
                val connectionId = if (json.has("connectionId")) json.optString("connectionId") else null
                val sortBy = json.optString("sortBy", "STARRED_AT")
                val sortOrder = json.optString("sortOrder", "DESC")
                val items = storage().list(connectionId, sortBy, sortOrder)
                // 必须用 JSONObject + toMap()：裸 JSONArray 无法过桥，
                // Kotlin 侧会拿不到数组（表现为列表恒为空）。KRSftpModule.list 就是这么返回的。
                val result = JSONObject()
                result.put("items", arrOf(items))
                callback?.invoke(result.toMap())
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun isFavorited(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val json = params.toJSONObjectSafely()
                val id = storage().findId(json.optString("connectionId"), json.optString("remotePath"))
                callback?.invoke(mapOf("id" to (id ?: "")))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun update(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val json = params.toJSONObjectSafely()
                storage().update(json.optString("id"), json)
                callback?.invoke(mapOf("ok" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun search(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val json = params.toJSONObjectSafely()
                val keyword = json.optString("keyword")
                val items = storage().search(keyword)
                // 必须用 JSONObject + toMap()：裸 JSONArray 无法过桥，
                // Kotlin 侧会拿不到数组（表现为列表恒为空）。KRSftpModule.list 就是这么返回的。
                val result = JSONObject()
                result.put("items", arrOf(items))
                callback?.invoke(result.toMap())
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
        const val MODULE_NAME = "KRSftpFavoritesModule"
        const val METHOD_ADD = "add"
        const val METHOD_REMOVE = "remove"
        const val METHOD_REMOVE_BY_CONNECTION = "removeByConnection"
        const val METHOD_LIST = "list"
        const val METHOD_IS_FAVORITED = "isFavorited"
        const val METHOD_UPDATE = "update"
        const val METHOD_SEARCH = "search"
    }
}

/** 全局单例 Holder（§21.7.4） */
object KRSftpFavoritesModuleHolder {
    val instance: KRSftpFavoritesModule by lazy { KRSftpFavoritesModule() }
}
