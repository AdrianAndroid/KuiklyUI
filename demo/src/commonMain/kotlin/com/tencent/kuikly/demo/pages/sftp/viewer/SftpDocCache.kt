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
package com.tencent.kuikly.demo.pages.sftp.viewer

import com.tencent.kuikly.demo.pages.sftp.viewer.md.MdBlock

/**
 * 已装载文档的内存缓存（LRU）。
 *
 * 目的：**先把文档缓存下来，再做查看/换行/字号/编辑等操作**——
 * - 重新打开同一文件时不再走 SFTP 分块读取与解码（秒开）；
 * - 解析结果（blocks/outline）一并缓存，避免重复解析；
 * - 编辑并保存后由调用方回写缓存，保证缓存与远端一致。
 *
 * 纯 commonMain（无平台 API），六端共用。容量按「文档数」限制，
 * 且对单篇超大文档做字节上限保护，避免内存不可控。
 *
 * 线程约定：仅在 UI 线程访问（与页面状态一致），因此不使用平台锁
 *（`@Synchronized` 属于 kotlin.jvm，在 JS/Native 目标不可用）。
 */
internal data class CachedDoc(
    val text: String,
    val encoding: String,
    val truncated: Boolean,
    val byteCount: Long,
    val blocks: List<MdBlock>,
    val outline: List<Pair<Int, String>>
)

internal object SftpDocCache {

    /** 最多缓存文档数 */
    private const val MAX_ENTRIES = 8
    /** 单篇缓存文本上限（超过不缓存，避免内存过大） */
    private const val MAX_TEXT_CHARS = 3 * 1024 * 1024

    private val lru = LinkedHashMap<String, CachedDoc>()

    fun key(connectionId: String, remotePath: String, size: Long): String =
        "$connectionId::$remotePath::$size"

    fun get(key: String): CachedDoc? {
        val doc = lru.remove(key) ?: return null
        lru[key] = doc // 触达后移到队尾（最近使用）
        return doc
    }

    fun put(key: String, doc: CachedDoc) {
        if (key.isEmpty()) return
        if (doc.text.length > MAX_TEXT_CHARS) return
        lru.remove(key)
        lru[key] = doc
        while (lru.size > MAX_ENTRIES) {
            val oldest = lru.keys.firstOrNull() ?: break
            lru.remove(oldest)
        }
    }

    fun remove(key: String) {
        lru.remove(key)
    }

    fun clear() {
        lru.clear()
    }

    fun size(): Int = lru.size
}
