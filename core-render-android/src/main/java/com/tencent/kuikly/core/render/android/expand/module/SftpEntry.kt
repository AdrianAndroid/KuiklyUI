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

import org.json.JSONObject

/**
 * SFTP 目录项（Android 端数据类，§3.1 / §21.2.1）
 *
 * 与 commonMain [com.tencent.kuikly.core.module.sftp.SftpEntry] 字段一一对应，
 * 但本类是 Android 端独立类型，避免 commonMain 类型与 Android 模块耦合。
 */
data class SftpEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    val permission: String,
    val uid: Int = -1,
    val gid: Int = -1,
    val owner: String? = null,
    val group: String? = null,
    val isSymlink: Boolean = false,
    val symlinkTarget: String? = null,
    val followsTarget: Boolean = false
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("name", name)
        json.put("path", path)
        json.put("isDir", isDir)
        json.put("size", size)
        json.put("mtime", mtime)
        json.put("permission", permission)
        if (uid >= 0) json.put("uid", uid)
        if (gid >= 0) json.put("gid", gid)
        owner?.let { json.put("owner", it) }
        group?.let { json.put("group", it) }
        if (isSymlink) {
            json.put("isSymlink", true)
            symlinkTarget?.let { json.put("symlinkTarget", it) }
            json.put("followsTarget", followsTarget)
        }
        return json
    }
}
