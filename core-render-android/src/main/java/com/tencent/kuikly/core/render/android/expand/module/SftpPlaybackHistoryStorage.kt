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
 * 播放历史持久化（SharedPreferences，§21.5.1）
 *
 * - 容量 2000 条 LRU
 * - 简化版：每次 upsert 全量读 + 写；Phase 1.2 改为 Room/SQLite
 */
class SftpPlaybackHistoryStorage(private val context: Context?) {

    private val prefs by lazy {
        context?.applicationContext?.getSharedPreferences("sftp_playback_history", Context.MODE_PRIVATE)
    }

    fun upsert(record: JSONObject) {
        val id = record.optString("id").ifEmpty { buildId(record) } // 空 id 视为未提供
        record.put("id", id)
        val all = loadAll()
        var found = false
        for (i in 0 until all.length()) {
            val it = all.optJSONObject(i) ?: continue
            if (it.optString("id") == id) {
                all.put(i, record)
                found = true
                break
            }
        }
        if (!found) all.put(record)
        // LRU 裁剪到 2000
        val trimmed = trimToCapacity(all, MAX_CAPACITY)
        saveAll(trimmed)
    }

    fun get(connectionId: String, remotePath: String): JSONObject? {
        val all = loadAll()
        val id = buildId(connectionId, remotePath)
        for (i in 0 until all.length()) {
            val it = all.optJSONObject(i) ?: continue
            if (it.optString("id") == id) return it
        }
        return null
    }

    fun listByDirectory(connectionId: String, directoryPath: String): List<JSONObject> {
        val all = loadAll()
        val list = mutableListOf<JSONObject>()
        for (i in 0 until all.length()) {
            val it = all.optJSONObject(i) ?: continue
            if (it.optString("connectionId") == connectionId) {
                val path = it.optString("remotePath")
                val parent = path.substringBeforeLast('/', "/")
                if (parent == directoryPath) list.add(it)
            }
        }
        return list.sortedByDescending { it.optLong("lastPlayedAt") }
    }

    fun listByConnection(connectionId: String): List<JSONObject> {
        val all = loadAll()
        val list = mutableListOf<JSONObject>()
        for (i in 0 until all.length()) {
            val it = all.optJSONObject(i) ?: continue
            if (it.optString("connectionId") == connectionId) list.add(it)
        }
        return list.sortedByDescending { it.optLong("lastPlayedAt") }
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

    fun clearByConnection(connectionId: String) {
        val all = loadAll()
        val filtered = JSONArray()
        for (i in 0 until all.length()) {
            val it = all.optJSONObject(i)
            if (it?.optString("connectionId") != connectionId) filtered.put(it)
        }
        saveAll(filtered)
    }

    fun markCompleted(connectionId: String, remotePath: String) {
        val id = buildId(connectionId, remotePath)
        val all = loadAll()
        for (i in 0 until all.length()) {
            val it = all.optJSONObject(i) ?: continue
            if (it.optString("id") == id) {
                it.put("completed", true)
                it.put("position", it.optLong("duration"))
                break
            }
        }
        saveAll(all)
    }

    private fun buildId(connectionId: String, remotePath: String): String =
        "$connectionId|$remotePath".hashCode().toString(16)

    private fun buildId(record: JSONObject): String =
        buildId(record.optString("connectionId"), record.optString("remotePath"))

    private fun loadAll(): JSONArray {
        val str = prefs?.getString(KEY_ITEMS, "[]") ?: "[]"
        return try { JSONArray(str) } catch (e: Exception) { JSONArray() }
    }

    private fun saveAll(arr: JSONArray) {
        prefs?.edit()?.putString(KEY_ITEMS, arr.toString())?.apply()
    }

    private fun trimToCapacity(arr: JSONArray, max: Int): JSONArray {
        if (arr.length() <= max) return arr
        // 按 lastPlayedAt 排序后保留最新的 max 条
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { list.add(it) }
        }
        val sorted = list.sortedByDescending { it.optLong("lastPlayedAt") }.take(max)
        val result = JSONArray()
        sorted.forEach { result.put(it) }
        return result
    }

    companion object {
        private const val KEY_ITEMS = "items"
        private const val MAX_CAPACITY = 2000
    }
}
