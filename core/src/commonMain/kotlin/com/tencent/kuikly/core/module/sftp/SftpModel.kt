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

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 便捷扩展：opt 任意类型 */
internal fun JSONObject.optAny(key: String): Any? = if (has(key)) opt(key) else null
/** 便捷扩展：optString 默认空 */
internal fun JSONObject.str(key: String): String = optString(key, "")
/** 便捷扩展：optBoolean 默认 false */
internal fun JSONObject.bool(key: String): Boolean = optBoolean(key, false)
/** 便捷扩展：optInt 默认 fallback */
internal fun JSONObject.int(key: String, fallback: Int): Int = optInt(key, fallback)
/** 便捷扩展：optLong 默认 fallback */
internal fun JSONObject.lng(key: String, fallback: Long): Long = optLong(key, fallback)
/** 便捷扩展：optString 可空 */
internal fun JSONObject.strOrNull(key: String): String? = if (has(key)) optString(key, "").ifEmpty { null } else null
/** 便捷扩展：opt JSONObject 可空 */
internal fun JSONObject.objOrNull(key: String): JSONObject? = optJSONObject(key)
/** 便捷扩展：opt JSONArray 可空 */
internal fun JSONObject.arrOrNull(key: String): JSONArray? = optJSONArray(key)

/**
 * SFTP 连接参数（§3.1 / §21.1.1）
 */
data class SftpConnectParam(
    val host: String,
    val port: Int = 22,
    val user: String,
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
    val knownHosts: String? = null,
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val connectTimeoutMs: Int = 15000,
    val readTimeoutMs: Int = 30000,
    val keepAliveIntervalSec: Int = 15,
    val idleDisconnectSec: Int = 1800,
    val serverEncoding: String = "UTF-8",
    val compression: Boolean = false,
    val maxConcurrentChannels: Int = 4
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("host", host)
        json.put("port", port)
        json.put("user", user)
        password?.let { json.put("password", it) }
        privateKey?.let { json.put("privateKey", it) }
        passphrase?.let { json.put("passphrase", it) }
        knownHosts?.let { json.put("knownHosts", it) }
        json.put("authMethod", authMethod.name)
        json.put("connectTimeoutMs", connectTimeoutMs)
        json.put("readTimeoutMs", readTimeoutMs)
        json.put("keepAliveIntervalSec", keepAliveIntervalSec)
        json.put("idleDisconnectSec", idleDisconnectSec)
        json.put("serverEncoding", serverEncoding)
        json.put("compression", compression)
        json.put("maxConcurrentChannels", maxConcurrentChannels)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): SftpConnectParam = SftpConnectParam(
            host = json.str("host"),
            port = json.int("port", 22),
            user = json.str("user"),
            password = json.strOrNull("password"),
            privateKey = json.strOrNull("privateKey"),
            passphrase = json.strOrNull("passphrase"),
            knownHosts = json.strOrNull("knownHosts"),
            authMethod = json.strOrNull("authMethod")?.let { AuthMethod.valueOf(it) } ?: AuthMethod.PASSWORD,
            connectTimeoutMs = json.int("connectTimeoutMs", 15000),
            readTimeoutMs = json.int("readTimeoutMs", 30000),
            keepAliveIntervalSec = json.int("keepAliveIntervalSec", 15),
            idleDisconnectSec = json.int("idleDisconnectSec", 1800),
            serverEncoding = json.strOrNull("serverEncoding") ?: "UTF-8",
            compression = json.bool("compression"),
            maxConcurrentChannels = json.int("maxConcurrentChannels", 4)
        )
    }
}

enum class AuthMethod { PASSWORD, PUBLIC_KEY, AGENT }
enum class OverwriteMode { OVERWRITE, SKIP, FAIL, RENAME_APPEND_SUFFIX }
enum class SftpFavoriteSortBy { STARRED_AT, NAME, CONNECTION_LABEL, MTIME }
enum class SortOrder { ASC, DESC }

/**
 * 目录项（§3.1 / §21.2.1）
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
    val mimeHint: String? = null,
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
        mimeHint?.let { json.put("mimeHint", it) }
        if (isSymlink) {
            json.put("isSymlink", true)
            symlinkTarget?.let { json.put("symlinkTarget", it) }
            json.put("followsTarget", followsTarget)
        }
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): SftpEntry = SftpEntry(
            name = json.str("name"),
            path = json.str("path"),
            isDir = json.bool("isDir"),
            size = json.lng("size", 0L),
            mtime = json.lng("mtime", 0L),
            permission = json.str("permission"),
            uid = json.int("uid", -1),
            gid = json.int("gid", -1),
            owner = json.strOrNull("owner"),
            group = json.strOrNull("group"),
            mimeHint = json.strOrNull("mimeHint"),
            isSymlink = json.bool("isSymlink"),
            symlinkTarget = json.strOrNull("symlinkTarget"),
            followsTarget = json.bool("followsTarget")
        )

        fun listFromJson(arr: JSONArray): List<SftpEntry> =
            (0 until arr.length()).map { fromJson(arr.optJSONObject(it)!!) }
    }
}

data class SftpCopyResult(
    val success: Boolean,
    val copiedCount: Int,
    val failedCount: Int,
    val errors: List<String> = emptyList()
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("success", success)
        json.put("copiedCount", copiedCount)
        json.put("failedCount", failedCount)
        val arr = JSONArray()
        errors.forEach { arr.put(it) }
        json.put("errors", arr)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): SftpCopyResult {
            val errors: List<String> = json.arrOrNull("errors")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it) }
            } ?: emptyList()
            return SftpCopyResult(
                success = json.bool("success"),
                copiedCount = json.int("copiedCount", 0),
                failedCount = json.int("failedCount", 0),
                errors = errors
            )
        }
    }
}

data class SftpFavorite(
    val id: String,
    val connectionId: String,
    val connectionLabel: String,
    val remotePath: String,
    val name: String,
    val isDir: Boolean,
    val size: Long = 0L,
    val starredAt: Long,
    val note: String? = null,
    val iconOverride: SftpFavoriteIcon? = null
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("connectionId", connectionId)
        json.put("connectionLabel", connectionLabel)
        json.put("remotePath", remotePath)
        json.put("name", name)
        json.put("isDir", isDir)
        json.put("size", size)
        json.put("starredAt", starredAt)
        note?.let { json.put("note", it) }
        iconOverride?.let { json.put("iconOverride", it.name) }
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): SftpFavorite = SftpFavorite(
            id = json.str("id"),
            connectionId = json.str("connectionId"),
            connectionLabel = json.str("connectionLabel"),
            remotePath = json.str("remotePath"),
            name = json.str("name"),
            isDir = json.bool("isDir"),
            size = json.lng("size", 0L),
            starredAt = json.lng("starredAt", 0L),
            note = json.strOrNull("note"),
            iconOverride = json.strOrNull("iconOverride")?.let { SftpFavoriteIcon.valueOf(it) }
        )

        fun listFromJson(arr: JSONArray): List<SftpFavorite> =
            (0 until arr.length()).map { fromJson(arr.optJSONObject(it)!!) }
    }
}

enum class SftpFavoriteIcon { DEFAULT, FOLDER, VIDEO, MUSIC, IMAGE, DOC, ARCHIVE, CODE, CUSTOM }

data class SftpBatchTask(
    val sessionId: String,
    val action: SftpBatchAction,
    val items: List<String>,
    val targetDir: String? = null,
    val localDir: String? = null
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("sessionId", sessionId)
        json.put("action", action.name)
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        json.put("items", arr)
        targetDir?.let { json.put("targetDir", it) }
        localDir?.let { json.put("localDir", it) }
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): SftpBatchTask {
            val items: List<String> = json.arrOrNull("items")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it) }
            } ?: emptyList()
            return SftpBatchTask(
                sessionId = json.str("sessionId"),
                action = SftpBatchAction.valueOf(json.str("action")),
                items = items,
                targetDir = json.strOrNull("targetDir"),
                localDir = json.strOrNull("localDir")
            )
        }
    }
}

enum class SftpBatchAction { DELETE, MOVE, COPY, DOWNLOAD }

/**
 * 播放历史记录（§20.2）
 */
data class SftpPlaybackRecord(
    val id: String,
    val connectionId: String,
    val connectionLabel: String,
    val remotePath: String,
    val name: String,
    val duration: Long,
    val position: Long,
    val completed: Boolean,
    val lastPlayedAt: Long,
    val size: Long = 0L,
    val posterTimeMs: Long? = null
) {
    val progressPercent: Int
        get() = if (duration <= 0L) 0 else ((position.toDouble() / duration.toDouble() * 100).toInt()).coerceIn(0, 100)

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("connectionId", connectionId)
        json.put("connectionLabel", connectionLabel)
        json.put("remotePath", remotePath)
        json.put("name", name)
        json.put("duration", duration)
        json.put("position", position)
        json.put("completed", completed)
        json.put("lastPlayedAt", lastPlayedAt)
        json.put("size", size)
        posterTimeMs?.let { json.put("posterTimeMs", it) }
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): SftpPlaybackRecord = SftpPlaybackRecord(
            id = json.str("id"),
            connectionId = json.str("connectionId"),
            connectionLabel = json.str("connectionLabel"),
            remotePath = json.str("remotePath"),
            name = json.str("name"),
            duration = json.lng("duration", 0L),
            position = json.lng("position", 0L),
            completed = json.bool("completed"),
            lastPlayedAt = json.lng("lastPlayedAt", 0L),
            size = json.lng("size", 0L),
            posterTimeMs = json.strOrNull("posterTimeMs")?.toLongOrNull()
        )

        fun listFromJson(arr: JSONArray): List<SftpPlaybackRecord> =
            (0 until arr.length()).map { fromJson(arr.optJSONObject(it)!!) }

        /** 生成稳定 id */
        fun buildId(connectionId: String, remotePath: String): String =
            "$connectionId|$remotePath".hashCode().toString(16)
    }
}

/**
 * 已保存的 SFTP 连接配置（§17.3.1 SftpHomePage 连接列表的持久化模型）
 *
 * - 与 [SftpConnectParam] 区别：[SftpConnectParam] 是运行时连接入参，[SftpConnection] 是持久化记录
 * - `id` 作为 [SftpFavorite.connectionId] / [SftpPlaybackRecord.connectionId] 的关联键（§3.4 / §20）
 * - 密码/密钥存储在原生侧 EncryptedSharedPreferences / Keychain / Huks（§21.4）
 */
data class SftpConnection(
    val id: String,                 // UUID 或 host:port/user 合成 id
    val label: String,              // 用户可读别名（如 "测试服务器"）
    val host: String,
    val port: Int = 22,
    val user: String,
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val password: String? = null,   // 原生侧加密存储；commonMain 仅作透传
    val privateKey: String? = null,
    val passphrase: String? = null,
    val knownHosts: String? = null,
    val serverEncoding: String = "UTF-8",
    val compression: Boolean = false,
    val keepAliveIntervalSec: Int = 15,
    val idleDisconnectSec: Int = 1800,
    val createdAt: Long = 0L,       // epoch 毫秒
    val lastUsedAt: Long = 0L       // epoch 毫秒
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("label", label)
        json.put("host", host)
        json.put("port", port)
        json.put("user", user)
        json.put("authMethod", authMethod.name)
        password?.let { json.put("password", it) }
        privateKey?.let { json.put("privateKey", it) }
        passphrase?.let { json.put("passphrase", it) }
        knownHosts?.let { json.put("knownHosts", it) }
        json.put("serverEncoding", serverEncoding)
        json.put("compression", compression)
        json.put("keepAliveIntervalSec", keepAliveIntervalSec)
        json.put("idleDisconnectSec", idleDisconnectSec)
        json.put("createdAt", createdAt)
        json.put("lastUsedAt", lastUsedAt)
        return json
    }

    /** 转换为运行时连接入参（用于调 [SftpModule.connect]） */
    fun toConnectParam(): SftpConnectParam = SftpConnectParam(
        host = host,
        port = port,
        user = user,
        password = password,
        privateKey = privateKey,
        passphrase = passphrase,
        knownHosts = knownHosts,
        authMethod = authMethod,
        keepAliveIntervalSec = keepAliveIntervalSec,
        idleDisconnectSec = idleDisconnectSec,
        serverEncoding = serverEncoding,
        compression = compression
    )

    companion object {
        fun fromJson(json: JSONObject): SftpConnection = SftpConnection(
            id = json.str("id"),
            label = json.strOrNull("label") ?: "",
            host = json.str("host"),
            port = json.int("port", 22),
            user = json.str("user"),
            authMethod = json.strOrNull("authMethod")?.let { AuthMethod.valueOf(it) } ?: AuthMethod.PASSWORD,
            password = json.strOrNull("password"),
            privateKey = json.strOrNull("privateKey"),
            passphrase = json.strOrNull("passphrase"),
            knownHosts = json.strOrNull("knownHosts"),
            serverEncoding = json.strOrNull("serverEncoding") ?: "UTF-8",
            compression = json.bool("compression"),
            keepAliveIntervalSec = json.int("keepAliveIntervalSec", 15),
            idleDisconnectSec = json.int("idleDisconnectSec", 1800),
            createdAt = json.lng("createdAt", 0L),
            lastUsedAt = json.lng("lastUsedAt", 0L)
        )

        fun listFromJson(arr: JSONArray): List<SftpConnection> =
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { fromJson(it) }

        /** 合成稳定 id（无 UUID 时用 host:port/user） */
        fun buildId(host: String, port: Int, user: String): String =
            "$host:$port/$user"
    }
}
