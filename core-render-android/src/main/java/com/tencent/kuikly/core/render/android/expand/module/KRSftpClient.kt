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

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.Session
import org.json.JSONObject
import java.util.Vector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * SFTP 客户端（Android，基于 JSch，§3.5 / §21.1.1）
 *
 * - Session 复用：sessionId → [SftpSession] 映射；空闲 30 分钟自动断开（§21.1.3）
 * - 文件句柄：fileHandleId → [SftpFileHandle] 映射；用于流式 read
 * - Channel 池：单 session 上最多 4 个并发 channel（§21.1.1 maxConcurrentChannels）
 *
 * **Phase 1 简化**：当前先实现最小可用版本，Session/Channel 池与空闲回收 Phase 1.2 接入。
 */
object KRSftpClient {

    private val sessions = ConcurrentHashMap<String, SftpSession>()
    private val fileHandles = ConcurrentHashMap<String, SftpFileHandle>()
    private val sessionIdCounter = AtomicLong(0)
    private val fileHandleIdCounter = AtomicLong(0)

    /** 连接 → 返回 sessionId */
    fun connect(params: JSONObject): String {
        val host = params.optString("host")
        val port = params.optInt("port", 22)
        val user = params.optString("user")
        val password = params.optString("password", "")
        val privateKey = params.optString("privateKey", "")
        val passphrase = params.optString("passphrase", "")
        val connectTimeoutMs = params.optInt("connectTimeoutMs", 15000)
        val keepAliveIntervalSec = params.optInt("keepAliveIntervalSec", 15)
        val compression = params.optBoolean("compression", false)

        val jsch = JSch()
        if (privateKey.isNotEmpty()) {
            if (passphrase.isNotEmpty()) {
                jsch.addIdentity(user, privateKey.toByteArray(), null, passphrase.toByteArray())
            } else {
                jsch.addIdentity(user, privateKey.toByteArray(), null, null)
            }
        }
        val session = jsch.getSession(user, host, port)
        if (password.isNotEmpty()) session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "no")  // TODO Phase 1.1: 接入 known_hosts 校验
        if (compression) session.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
        session.connect(connectTimeoutMs)
        session.serverVersion  // 触发握手
        session.setConfig("KeepAlive", "yes")
        session.timeout = keepAliveIntervalSec * 1000

        val sessionId = "sftp-${sessionIdCounter.incrementAndGet()}"
        sessions[sessionId] = SftpSession(sessionId, session)
        return sessionId
    }

    fun disconnect(sessionId: String) {
        sessions.remove(sessionId)?.disconnect()
    }

    fun list(sessionId: String, remotePath: String): List<SftpEntry> {
        val session = sessions[sessionId] ?: throw IllegalStateException("invalid sessionId")
        return session.withChannel { channel ->
            val entries = mutableListOf<SftpEntry>()
            @Suppress("UNCHECKED_CAST")
            val vector = channel.ls(remotePath) as Vector<ChannelSftp.LsEntry>
            for (e in vector) {
                val name = e.filename
                if (name == "." || name == "..") continue
                val attrs = e.attrs
                val isDir = attrs.isDir
                val isSymlink = attrs.isLink
                val path = if (remotePath.endsWith("/")) "$remotePath$name" else "$remotePath/$name"
                entries.add(
                    SftpEntry(
                        name = name,
                        path = path,
                        isDir = isDir,
                        size = attrs.size,
                        mtime = attrs.mTime.toLong(),
                        permission = attrs.permissionsString,
                        uid = attrs.uId,
                        gid = attrs.gId,
                        isSymlink = isSymlink,
                        followsTarget = isSymlink && !isDir
                    )
                )
            }
            entries
        }
    }

    fun stat(sessionId: String, remotePath: String, followSymlink: Boolean): SftpEntry {
        val session = sessions[sessionId] ?: throw IllegalStateException("invalid sessionId")
        return session.withChannel { channel ->
            val attrs = if (followSymlink) channel.stat(remotePath) else channel.lstat(remotePath)
            val name = remotePath.substringAfterLast('/')
            SftpEntry(
                name = name,
                path = remotePath,
                isDir = attrs.isDir,
                size = attrs.size,
                mtime = attrs.mTime.toLong(),
                permission = attrs.permissionsString,
                uid = attrs.uId,
                gid = attrs.gId,
                isSymlink = attrs.isLink
            )
        }
    }

    fun openRead(sessionId: String, remotePath: String): String {
        val session = sessions[sessionId] ?: throw IllegalStateException("invalid sessionId")
        val fileHandleId = "fh-${fileHandleIdCounter.incrementAndGet()}"
        val channel = session.createChannel()
        channel.connect()
        // 不预取顺序流：JSch 的 InputStream 只能顺序读，随机读在 read() 里按 offset 打开
        fileHandles[fileHandleId] = SftpFileHandle(fileHandleId, channel, remotePath)
        return fileHandleId
    }

    fun read(fileHandleId: String, offset: Long, length: Int): ByteArray {
        val handle = fileHandles[fileHandleId] ?: throw IllegalStateException("invalid fileHandleId")
        return handle.read(offset, length)
    }

    fun close(fileHandleId: String) {
        fileHandles.remove(fileHandleId)?.close()
    }

    /**
     * 下载远端文件到宿主缓存目录，返回本地绝对路径。
     *
     * 之前硬编码 `/data/data/com.tencent.kuikly.demo/cache/...`：既是别的应用包名
     * （必然写失败），也只把文件名当作路径回传。
     */
    fun download(params: JSONObject): String {
        val sessionId = params.optString("sessionId")
        val remotePath = params.optString("remotePath")
        val localName = params.optString("localName").ifEmpty { remotePath.substringAfterLast('/') }
        val cacheDir = params.optString("cacheDir").ifEmpty {
            throw IllegalStateException("cacheDir required")
        }
        val session = sessions[sessionId] ?: throw IllegalStateException("invalid sessionId")
        val dir = java.io.File(cacheDir).apply { if (!exists()) mkdirs() }
        val dest = java.io.File(dir, localName.ifEmpty { "download" })
        session.withChannel { channel -> channel.get(remotePath, dest.absolutePath) }
        return dest.absolutePath
    }

    fun upload(params: JSONObject): Float {
        val sessionId = params.optString("sessionId")
        val localPath = params.optString("localPath")
        val remotePath = params.optString("remotePath")
        val session = sessions[sessionId] ?: throw IllegalStateException("invalid sessionId")
        session.withChannel { channel ->
            channel.put(localPath, remotePath)
        }
        return 1.0f
    }

    fun mkdir(params: JSONObject) {
        val sessionId = params.optString("sessionId")
        val remotePath = params.optString("remotePath")
        val recursive = params.optBoolean("recursive", true)
        val session = sessions[sessionId] ?: throw IllegalStateException("invalid sessionId")
        session.withChannel { channel ->
            if (recursive) mkdirs(channel, remotePath) else channel.mkdir(remotePath)
        }
    }

    private fun mkdirs(channel: ChannelSftp, path: String) {
        try {
            channel.stat(path)
            return  // 已存在
        } catch (e: SftpException) { /* 不存在，继续 */ }
        val parent = path.substringBeforeLast('/', "/")
        if (parent != path) mkdirs(channel, parent)
        try { channel.mkdir(path) } catch (e: SftpException) { /* 已存在则忽略 */ }
    }

    fun rm(params: JSONObject) {
        val sessionId = params.optString("sessionId")
        val remotePath = params.optString("remotePath")
        val recursive = params.optBoolean("recursive", false)
        val session = sessions[sessionId] ?: throw IllegalStateException("invalid sessionId")
        session.withChannel { channel ->
            if (recursive) rmRecursive(channel, remotePath) else channel.rm(remotePath)
        }
    }

    private fun rmRecursive(channel: ChannelSftp, path: String) {
        try {
            val stat = channel.stat(path)
            if (stat.isDir) {
                @Suppress("UNCHECKED_CAST")
                val vector = channel.ls(path) as Vector<ChannelSftp.LsEntry>
                for (e in vector) {
                    if (e.filename == "." || e.filename == "..") continue
                    val child = if (path.endsWith("/")) "$path${e.filename}" else "$path/${e.filename}"
                    rmRecursive(channel, child)
                }
                channel.rmdir(path)
            } else {
                channel.rm(path)
            }
        } catch (e: SftpException) {
            // 文件不存在则忽略
        }
    }

    fun rename(params: JSONObject) {
        val sessionId = params.optString("sessionId")
        val oldPath = params.optString("oldPath")
        val newPath = params.optString("newPath")
        val session = sessions[sessionId] ?: throw IllegalStateException("invalid sessionId")
        session.withChannel { channel -> channel.rename(oldPath, newPath) }
    }

    fun move(params: JSONObject) {
        val sessionId = params.optString("sessionId")
        val srcPath = params.optString("srcPath")
        val destDir = params.optString("destDir")
        val session = sessions[sessionId] ?: throw IllegalStateException("invalid sessionId")
        session.withChannel { channel ->
            val name = srcPath.substringAfterLast('/')
            val destPath = if (destDir.endsWith("/")) "$destDir$name" else "$destDir/$name"
            channel.rename(srcPath, destPath)
        }
    }

    fun copy(params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId")
        val srcPath = params.optString("srcPath")
        val destPath = params.optString("destPath")
        val cacheDir = params.optString("cacheDir").ifEmpty {
            throw IllegalStateException("cacheDir required")
        }
        val session = sessions[sessionId] ?: throw IllegalStateException("invalid sessionId")
        val dir = java.io.File(cacheDir).apply { if (!exists()) mkdirs() }
        var copied = 0
        var failed = 0
        session.withChannel { channel ->
            copyEntry(channel, dir, srcPath, destPath, 0, { copied++ }, { failed++ })
        }
        val result = JSONObject()
        result.put("success", failed == 0)
        result.put("copiedCount", copied)
        result.put("failedCount", failed)
        return result
    }

    /**
     * 远端→远端复制：SFTP 无服务端拷贝原语，文件走「下载到临时文件 → 上传」，目录递归；
     * 临时文件放在宿主 cacheDir。
     */
    private fun copyEntry(
        channel: ChannelSftp,
        tmpDir: java.io.File,
        src: String,
        dest: String,
        depth: Int,
        onCopied: () -> Unit,
        onFailed: () -> Unit
    ) {
        if (depth > 64) { onFailed(); return }
        val stat = try { channel.stat(src) } catch (e: Exception) {
            onFailed(); return
        }
        if (stat.isDir) {
            try { mkdirs(channel, dest) } catch (e: Exception) { /* 已存在 */ }
            @Suppress("UNCHECKED_CAST")
            val vector = try { channel.ls(src) as Vector<ChannelSftp.LsEntry> } catch (e: Exception) {
                onFailed(); return
            }
            for (e in vector) {
                if (e.filename == "." || e.filename == "..") continue
                val childSrc = if (src.endsWith("/")) "$src${e.filename}" else "$src/${e.filename}"
                val childDest = if (dest.endsWith("/")) "$dest${e.filename}" else "$dest/${e.filename}"
                copyEntry(channel, tmpDir, childSrc, childDest, depth + 1, onCopied, onFailed)
            }
            return
        }
        val tmp = java.io.File(tmpDir, ".sftp-copy-${System.nanoTime()}")
        try {
            channel.get(src, tmp.absolutePath)
            channel.put(tmp.absolutePath, dest)
            onCopied()
        } catch (e: Exception) {
            onFailed()
        } finally {
            tmp.delete()
        }
    }

    fun chmod(params: JSONObject) {
        val sessionId = params.optString("sessionId")
        val remotePath = params.optString("remotePath")
        val mode = params.optString("mode")
        val session = sessions[sessionId] ?: throw IllegalStateException("invalid sessionId")
        session.withChannel { channel -> channel.chmod(Integer.parseInt(mode, 8), remotePath) }
    }

    fun chown(params: JSONObject) {
        val sessionId = params.optString("sessionId")
        val remotePath = params.optString("remotePath")
        val uid = params.optInt("uid")
        val gid = params.optInt("gid")
        val session = sessions[sessionId] ?: throw IllegalStateException("invalid sessionId")
        session.withChannel { channel -> channel.chown(uid, remotePath); channel.chgrp(gid, remotePath) }
    }

    fun setMtime(params: JSONObject) {
        val sessionId = params.optString("sessionId")
        val remotePath = params.optString("remotePath")
        // 上层传的是毫秒；SFTP setMtime 只接受秒级 int（JSch 0.1.55 仅有 (path, mtime) 两参版本）
        val mtime = (params.optLong("mtime") / 1000L).toInt()
        val session = sessions[sessionId] ?: throw IllegalStateException("invalid sessionId")
        session.withChannel { channel -> channel.setMtime(remotePath, mtime) }
    }

    /**
     * 批量任务：按 action 分发 DELETE/MOVE/COPY/DOWNLOAD。
     *
     * 之前直接 `return 1.0f` 伪报成功（§13.3 禁止）：出错必须抛出并由上层转错误码，
     * 否则用户会以为批量操作成功了。
     */
    fun batchTask(params: JSONObject): Float {
        val sessionId = params.optString("sessionId")
        val action = params.optString("action").ifEmpty { "DELETE" }.uppercase()
        val targetDir = params.optString("targetDir")
        val cacheDir = params.optString("cacheDir")
        val itemsArr = params.optJSONArray("items") ?: throw IllegalArgumentException("items required")
        if (itemsArr.length() == 0) throw IllegalArgumentException("items required")
        val session = sessions[sessionId] ?: throw IllegalStateException("invalid sessionId")

        val failures = mutableListOf<String>()
        session.withChannel { channel ->
            for (i in 0 until itemsArr.length()) {
                val item = itemsArr.optString(i)
                if (item.isEmpty()) continue
                try {
                    when (action) {
                        "DELETE" -> rmRecursive(channel, item)
                        "MOVE" -> {
                            val dest = destPathFor(item, targetDir)
                            mkdirs(channel, dest.substringBeforeLast('/', "/"))
                            channel.rename(item, dest)
                        }
                        "COPY" -> {
                            if (cacheDir.isEmpty()) throw IllegalStateException("cacheDir required")
                            val dest = destPathFor(item, targetDir)
                            mkdirs(channel, dest.substringBeforeLast('/', "/"))
                            val dir = java.io.File(cacheDir).apply { if (!exists()) mkdirs() }
                            var failed = false
                            copyEntry(channel, dir, item, dest, 0, {}, { failed = true })
                            if (failed) throw IllegalStateException("copy failed")
                        }
                        "DOWNLOAD" -> {
                            if (cacheDir.isEmpty()) throw IllegalStateException("cacheDir required")
                            val dir = java.io.File(cacheDir).apply { if (!exists()) mkdirs() }
                            channel.get(item, java.io.File(dir, item.substringAfterLast('/')).absolutePath)
                        }
                        else -> throw IllegalArgumentException("unsupported action: $action")
                    }
                } catch (e: Exception) {
                    failures.add("$item: ${e.message}")
                }
            }
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException(
                "batch $action failed ${failures.size}/${itemsArr.length()}: ${failures.first()}"
            )
        }
        return 1.0f
    }

    /** 批量操作目标路径：targetDir + 原文件名 */
    private fun destPathFor(srcPath: String, targetDir: String): String {
        val name = srcPath.substringAfterLast('/')
        if (targetDir.isEmpty()) return srcPath
        return if (targetDir.endsWith("/")) "$targetDir$name" else "$targetDir/$name"
    }

    /** 批量取消：同步串行执行无法中断，显式记录而非静默（§13.3） */
    fun cancelBatchTask(taskId: String) {
        com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
            .krLogAdapter?.e("KRSftpClient", "cancelBatchTask($taskId) no-op: 同步执行无法中断")
    }

    /** 关闭所有 session（应用退出时调） */
    fun shutdownAll() {
        sessions.values.forEach { it.disconnect() }
        sessions.clear()
        fileHandles.values.forEach { it.close() }
        fileHandles.clear()
    }
}

/** 一个 SFTP 会话（Session + Channel 池） */
private class SftpSession(val id: String, val session: Session) {
    private val channels = mutableListOf<ChannelSftp>()
    private val lock = Any()

    fun createChannel(): ChannelSftp {
        synchronized(lock) {
            val channel = session.openChannel("sftp") as ChannelSftp
            channels.add(channel)
            return channel
        }
    }

    fun <T> withChannel(block: (ChannelSftp) -> T): T {
        val channel = createChannel()
        channel.connect()
        try {
            return block(channel)
        } finally {
            channel.disconnect()
            synchronized(lock) { channels.remove(channel) }
        }
    }

    fun disconnect() {
        synchronized(lock) { channels.forEach { it.disconnect() }; channels.clear() }
        session.disconnect()
    }
}

/**
 * 随机读文件句柄。
 *
 * JSch 的 `get(src)` 返回的是**只能顺序读**的流，之前实现直接对它 `skip(offset)`：
 * offset 会被当成「相对当前位置」的位移，且多次 read 后位置早已推进 ——
 * 随机读返回错位数据，播放器解析 MP4 直接失败。
 * 这里改用 `get(src, monitor, skip)`：JSch 会把 skip 作为 SFTP 读偏移下发
 * （不会真的传输被跳过的字节），因此每次都能按绝对偏移取到正确数据。
 */
private class SftpFileHandle(
    val id: String,
    val channel: ChannelSftp,
    val remotePath: String
) {
    fun read(offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val stream = channel.get(remotePath, null, offset)
        try {
            val buf = ByteArray(length)
            var total = 0
            while (total < length) {
                val n = stream.read(buf, total, length - total)
                if (n <= 0) break
                total += n
            }
            return if (total == length) buf else buf.copyOf(total)
        } finally {
            try { stream.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    fun close() {
        channel.disconnect()
    }
}
