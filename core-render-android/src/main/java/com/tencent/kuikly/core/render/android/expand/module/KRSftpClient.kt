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
        val input = channel.get(remotePath)  // SFTPInputStream-like (JSch returns InputStream)
        fileHandles[fileHandleId] = SftpFileHandle(fileHandleId, channel, input, remotePath)
        return fileHandleId
    }

    fun read(fileHandleId: String, offset: Long, length: Int): ByteArray {
        val handle = fileHandles[fileHandleId] ?: throw IllegalStateException("invalid fileHandleId")
        return handle.read(offset, length)
    }

    fun close(fileHandleId: String) {
        fileHandles.remove(fileHandleId)?.close()
    }

    fun download(params: JSONObject): Float {
        val sessionId = params.optString("sessionId")
        val remotePath = params.optString("remotePath")
        val localName = params.optString("localName")
        val session = sessions[sessionId] ?: throw IllegalStateException("invalid sessionId")
        // TODO Phase 1.1: 接入本地缓存路径 + 进度回调
        session.withChannel { channel ->
            channel.get(remotePath, "/data/data/com.tencent.kuikly.demo/cache/$localName")
        }
        return 1.0f
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
        val session = sessions[sessionId] ?: throw IllegalStateException("invalid sessionId")
        // JSch 不直接支持 SFTP copy，需要 get + put
        // TODO Phase 1.2: 用 SFTPInputStream + SFTPOutputStream 流式 copy
        session.withChannel { channel ->
            // 简化：下载到临时文件再上传
            val tmpPath = "/data/data/com.tencent.kuikly.demo/cache/.sftp-tmp-${System.currentTimeMillis()}"
            channel.get(srcPath, tmpPath)
            channel.put(tmpPath, destPath)
            java.io.File(tmpPath).delete()
        }
        val result = JSONObject()
        result.put("success", true)
        result.put("copiedCount", 1)
        result.put("failedCount", 0)
        return result
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
        val mtime = params.optInt("mtime")
        val atime = params.optInt("atime", mtime)
        val session = sessions[sessionId] ?: throw IllegalStateException("invalid sessionId")
        // JSch ChannelSftp.setMtime(path, mtime, atime) 两个 int 参数（秒级 epoch）
        session.withChannel { channel -> channel.setMtime(remotePath, mtime, atime) }
    }

    fun batchTask(params: JSONObject): Float {
        // TODO Phase 1.2: 接入批量任务调度（DELETE/MOVE/COPY/DOWNLOAD）
        return 1.0f
    }

    fun cancelBatchTask(taskId: String) {
        // TODO Phase 1.2
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

/** 流式读文件句柄 */
private class SftpFileHandle(
    val id: String,
    val channel: ChannelSftp,
    val input: java.io.InputStream,
    val remotePath: String
) {
    fun read(offset: Long, length: Int): ByteArray {
        // JSch 不直接支持 random access；这里用 channel.get(remotePath, OutputStream, monitor, resume, offset)
        // 简化：用 input.skip + read
        val skipped = input.skip(offset)
        if (skipped < offset) return ByteArray(0)
        val buf = ByteArray(length)
        val read = input.read(buf)
        return if (read > 0) buf.copyOf(read) else ByteArray(0)
    }

    fun close() {
        try { input.close() } catch (e: Exception) { }
        channel.disconnect()
    }
}
