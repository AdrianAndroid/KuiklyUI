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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 收藏持久化（SharedPreferences，§21.4.1）
 *
 * - 存储格式：`SharedPreferences` 中 `items` 字段保存 JSON 数组字符串
 * - 简化版：每次 list 都全量读 + 全量过滤；Phase 1.2 改为 Room/SQLite
 */
class SftpFavoritesStorage(private val context: Context?) {

    private val prefs by lazy {
        context?.applicationContext?.getSharedPreferences("sftp_favorites", Context.MODE_PRIVATE)
    }

    fun add(item: JSONObject): String {
        val id = item.optString("id").ifEmpty { UUID.randomUUID().toString() } // 空 id 视为未提供
        item.put("id", id)
        if (!item.has("starredAt")) item.put("starredAt", System.currentTimeMillis())
        val all = loadAll()
        all.put(item)
        saveAll(all)
        return id
    }

    fun remove(id: String) {
        val all = loadAll()
        val filtered = JSONArray()
        for (i in 0 until all.length()) {
            val it = all.optJSONObject(i)
            if (it?.optString("id") != id) filtered.put(it)
        }
        saveAll(filtered)
    }

    fun removeByConnection(connectionId: String): Int {
        val all = loadAll()
        val filtered = JSONArray()
        var removed = 0
        for (i in 0 until all.length()) {
            val it = all.optJSONObject(i)
            if (it?.optString("connectionId") == connectionId) {
                removed++
            } else {
                filtered.put(it)
            }
        }
        saveAll(filtered)
        return removed
    }

    fun list(connectionId: String?, sortBy: String, sortOrder: String): List<JSONObject> {
        val all = loadAll()
        val list = mutableListOf<JSONObject>()
        for (i in 0 until all.length()) {
            val it = all.optJSONObject(i) ?: continue
            if (connectionId == null || it.optString("connectionId") == connectionId) list.add(it)
        }
        return sortList(list, sortBy, sortOrder)
    }

    fun findId(connectionId: String, remotePath: String): String? {
        val all = loadAll()
        for (i in 0 until all.length()) {
            val it = all.optJSONObject(i) ?: continue
            if (it.optString("connectionId") == connectionId && it.optString("remotePath") == remotePath) {
                return it.optString("id")
            }
        }
        return null
    }

    fun update(id: String, patch: JSONObject) {
        val all = loadAll()
        for (i in 0 until all.length()) {
            val it = all.optJSONObject(i) ?: continue
            if (it.optString("id") == id) {
                if (patch.has("note")) it.put("note", patch.optString("note"))
                if (patch.has("iconOverride")) it.put("iconOverride", patch.optString("iconOverride"))
            }
        }
        saveAll(all)
    }

    fun search(keyword: String): List<JSONObject> {
        val all = loadAll()
        val list = mutableListOf<JSONObject>()
        val lower = keyword.lowercase()
        for (i in 0 until all.length()) {
            val it = all.optJSONObject(i) ?: continue
            val name = it.optString("name").lowercase()
            val path = it.optString("remotePath").lowercase()
            val label = it.optString("connectionLabel").lowercase()
            if (name.contains(lower) || path.contains(lower) || label.contains(lower)) list.add(it)
        }
        return list
    }

    private fun loadAll(): JSONArray {
        val str = prefs?.getString(KEY_ITEMS, "[]") ?: "[]"
        return try { JSONArray(str) } catch (e: Exception) { JSONArray() }
    }

    private fun saveAll(arr: JSONArray) {
        prefs?.edit()?.putString(KEY_ITEMS, arr.toString())?.apply()
    }

    private fun sortList(list: MutableList<JSONObject>, sortBy: String, sortOrder: String): List<JSONObject> {
        val comparator = when (sortBy) {
            "NAME" -> compareBy<JSONObject> { it.optString("name") }
            "CONNECTION_LABEL" -> compareBy<JSONObject> { it.optString("connectionLabel") }
            "MTIME" -> compareBy<JSONObject> { it.optLong("mtime") }
            else -> compareBy<JSONObject> { it.optLong("starredAt") }
        }
        val sorted = if (sortOrder == "DESC") list.sortedWith(comparator.reversed()) else list.sortedWith(comparator)
        return sorted
    }

    companion object {
        private const val KEY_ITEMS = "items"
    }
}
