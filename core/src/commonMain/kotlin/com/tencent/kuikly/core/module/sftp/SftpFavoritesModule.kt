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
 * SFTP 收藏 Module（§3.4 / §7.2 / §21.4）
 *
 * 独立于 SFTP session，本地持久化；全局单例（§21.7.4）。
 */
class SftpFavoritesModule : Module() {
    override fun moduleName(): String = MODULE_NAME

    fun add(item: SftpFavorite, callback: (id: String?, error: SftpError?) -> Unit) {
        asyncToNativeMethod(METHOD_ADD, item.toJson()) { data ->
            val err = data?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(null, SftpError.fromJson(err))
            } else {
                callback(data?.str("id"), null)
            }
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

    fun removeByConnection(connectionId: String, callback: (success: Boolean, removedCount: Int, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("connectionId", connectionId)
        asyncToNativeMethod(METHOD_REMOVE_BY_CONNECTION, params) { data ->
            val err = data?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(false, 0, SftpError.fromJson(err))
            } else {
                callback(true, data?.int("removedCount", 0) ?: 0, null)
            }
        }
    }

    fun list(
        connectionId: String? = null,
        sortBy: SftpFavoriteSortBy = SftpFavoriteSortBy.STARRED_AT,
        sortOrder: SortOrder = SortOrder.DESC,
        callback: (items: List<SftpFavorite>, error: SftpError?) -> Unit
    ) {
        val params = JSONObject()
        connectionId?.let { params.put("connectionId", it) }
        params.put("sortBy", sortBy.name)
        params.put("sortOrder", sortOrder.name)
        asyncToNativeMethod(METHOD_LIST, params) { data ->
            val err = data?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(emptyList(), SftpError.fromJson(err))
            } else {
                val arr = data?.arrOrNull("items") ?: JSONArray()
                callback(SftpFavorite.listFromJson(arr), null)
            }
        }
    }

    fun isFavorited(connectionId: String, remotePath: String, callback: (id: String?) -> Unit) {
        val params = JSONObject()
        params.put("connectionId", connectionId)
        params.put("remotePath", remotePath)
        asyncToNativeMethod(METHOD_IS_FAVORITED, params) { data ->
            val id = data?.str("id")
            callback(id?.takeIf { it.isNotEmpty() })
        }
    }

    fun update(id: String, note: String?, iconOverride: SftpFavoriteIcon?, callback: (success: Boolean, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("id", id)
        note?.let { params.put("note", it) }
        iconOverride?.let { params.put("iconOverride", it.name) }
        asyncToNativeMethod(METHOD_UPDATE, params) { data ->
            val err = data?.str("error")
            callback(err.isNullOrEmpty(), if (err.isNullOrEmpty()) null else SftpError.fromJson(err))
        }
    }

    fun search(keyword: String, callback: (items: List<SftpFavorite>, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("keyword", keyword)
        asyncToNativeMethod(METHOD_SEARCH, params) { data ->
            val err = data?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(emptyList(), SftpError.fromJson(err))
            } else {
                val arr = data?.arrOrNull("items") ?: JSONArray()
                callback(SftpFavorite.listFromJson(arr), null)
            }
        }
    }

    companion object {
        const val MODULE_NAME = ModuleConst.SFTP_FAVORITES

        const val METHOD_ADD = "add"
        const val METHOD_REMOVE = "remove"
        const val METHOD_REMOVE_BY_CONNECTION = "removeByConnection"
        const val METHOD_LIST = "list"
        const val METHOD_IS_FAVORITED = "isFavorited"
        const val METHOD_UPDATE = "update"
        const val METHOD_SEARCH = "search"
    }
}
