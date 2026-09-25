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
package com.tencent.kuikly.demo.pages.sftp.cache

import com.tencent.kuikly.core.datetime.DateTime
import com.tencent.kuikly.core.module.sftp.SftpEntry
import com.tencent.kuikly.core.module.sftp.SftpError
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 缓存任务所需的宿主 I/O（由页面注入，避免 CacheManager 依赖具体 Page）。
 * - [list]：远端列目录（递归求体积 / 展开文件清单）
 * - [download]：把单个远端文件写到本地绝对路径（web/桌面由网关落盘；本地不可用时不应发起）
 */
internal interface CacheIo {
    fun list(sessionId: String, path: String, callback: (entries: List<SftpEntry>, error: SftpError?) -> Unit)
    fun download(sessionId: String, remotePath: String, localPath: String, callback: (success: Boolean, error: SftpError?) -> Unit)
}

/**
 * 目录/文件缓存管理器（commonMain，六端共用；Web/桌面已验证）。
 *
 * 设计：
 * - **全局单例**：任务跨页面存在（浏览页发起 → 缓存列表页查看），页面轮询 [version] 同步 UI。
 * - **顺序执行**：一次只下载一个文件（自动串行，避免打满连接）；每个文件完成后立即续跑，
 *   暂停/取消在文件边界生效。
 * - **状态机**见 [CacheEngine]（RUNNING/PAUSED/CANCELLED/DONE/FAILED）。
 *
 * 说明：进度按「文件」推进（单个大文件在完成前进度为 0），因为各端 download 只在上层回调完成事件。
 */
internal object CacheManager {

    /** 相邻两个文件之间的间隔（毫秒）：给 UI 渲染与暂停/取消留出窗口 */
    private const val STEP_GAP_MS = 150

    private val taskList = ArrayList<CacheTask>()
    private val runners = HashMap<String, Runner>()
    private var seq = 0

    /** 每次任务集合/进度变化自增；页面轮询它决定是否刷新（跨页面的轻量通知） */
    var version: Int = 0
        private set

    private class Runner(val io: CacheIo, val sessionId: String)

    private var inFlightId: String? = null

    // region —— 查询 ——

    fun snapshot(): List<CacheTask> = ArrayList(taskList)

    fun get(id: String): CacheTask? = taskList.firstOrNull { it.id == id }

    fun hasActive(): Boolean = taskList.any { it.state == CacheState.RUNNING || it.state == CacheState.PAUSED }

    /** 是否有任意任务（含已完成）：用于让悬浮条在任务结束后仍可打开查看结果 */
    fun hasAny(): Boolean = taskList.isNotEmpty()

    fun activeCount(): Int = taskList.count { it.state == CacheState.RUNNING || it.state == CacheState.PAUSED }

    /** 顶部悬浮条文案 */
    fun barSummary(): String {
        val running = taskList.count { it.state == CacheState.RUNNING }
        val paused = taskList.count { it.state == CacheState.PAUSED }
        val done = taskList.count { it.state == CacheState.DONE }
        if (taskList.isEmpty()) return "缓存列表"
        return "缓存 $running 进行中" + (if (paused > 0) " · $paused 暂停" else "") + (if (done > 0) " · $done 完成" else "")
    }

    // endregion

    // region —— 发起任务 ——

    /**
     * 递归求目录体积与文件清单（不落盘）。调用方拿到结果后决定是否弹「过大确认」。
     * [base] 为根目录，用于计算相对路径（本地落盘的目录结构）。
     */
    fun measureDir(
        io: CacheIo,
        sessionId: String,
        remoteDir: String,
        callback: (files: List<CacheFile>, totalBytes: Long, error: SftpError?) -> Unit
    ) {
        val acc = ArrayList<CacheFile>()
        walk(io, sessionId, remoteDir, remoteDir, acc, callback)
    }

    private fun walk(
        io: CacheIo,
        sessionId: String,
        base: String,
        dir: String,
        acc: MutableList<CacheFile>,
        done: (files: List<CacheFile>, totalBytes: Long, error: SftpError?) -> Unit
    ) {
        io.list(sessionId, dir) { entries, error ->
            if (error != null) {
                done(emptyList(), 0L, error)
                return@list
            }
            val dirs = ArrayList<SftpEntry>()
            for (e in entries) {
                // 跳过符号链接，避免目录环导致无限递归（与双栏传输语义一致：不跟随符号链接）
                if (e.isSymlink) continue
                if (e.isDir) {
                    dirs.add(e)
                } else {
                    acc.add(CacheFile(remotePath = e.path, relPath = relPath(base, e.path, e.name), size = e.size))
                }
            }
            var i = 0
            fun nextDir() {
                if (i >= dirs.size) {
                    done(ArrayList(acc), acc.sumOf { if (it.size > 0) it.size else 0L }, null)
                    return
                }
                val d = dirs[i++]
                walk(io, sessionId, base, d.path, acc) { _, _, err ->
                    if (err != null) done(emptyList(), 0L, err) else nextDir()
                }
            }
            nextDir()
        }
    }

    /** 单文件缓存（用户明确要求：单文件也可缓存）。返回任务 id。 */
    fun enqueueFile(io: CacheIo, sessionId: String, entry: SftpEntry, cacheRoot: String, overwrite: Boolean = true): String {
        val name = entry.name.ifEmpty { "file" }
        // 单文件直接落在缓存根下（不再套一层与文件同名的目录）
        val localDir = cacheRoot.trimEnd('/')
        val files = listOf(CacheFile(remotePath = entry.path, relPath = name, size = entry.size))
        val task = CacheEngine.buildTask(newId(), name, entry.path, localDir, files)
        addTask(task, io, sessionId)
        pump()
        return task.id
    }

    /** 目录缓存：传入 [measureDir] 得到的结果。返回任务 id。 */
    fun enqueueDir(io: CacheIo, sessionId: String, remoteDir: String, name: String, cacheRoot: String, files: List<CacheFile>): String {
        val safe = name.ifEmpty { "dir" }
        val localDir = joinLocal(cacheRoot, safe)
        val task = CacheEngine.buildTask(newId(), safe, remoteDir, localDir, files)
        addTask(task, io, sessionId)
        pump()
        return task.id
    }

    private fun addTask(task: CacheTask, io: CacheIo, sessionId: String) {
        taskList.add(task)
        runners[task.id] = Runner(io, sessionId)
        version++
    }

    // endregion

    // region —— 控制 ——

    fun pause(id: String) {
        update(id) { CacheEngine.pause(it) }
    }

    fun resume(id: String) {
        update(id) { CacheEngine.resume(it) }
        pump()
    }

    fun cancel(id: String) {
        val idx = taskList.indexOfFirst { it.id == id }
        if (idx >= 0) {
            taskList.removeAt(idx)
            runners.remove(id)
            if (inFlightId == id) inFlightId = null
            version++
        }
        pump()
    }

    /** 清掉已完成/失败的任务，仅保留进行中/暂停 */
    fun clearFinished() {
        val removable = taskList.filter { it.state == CacheState.DONE || it.state == CacheState.CANCELLED || it.state == CacheState.FAILED }
        if (removable.isEmpty()) return
        removable.forEach { t -> taskList.remove(t); runners.remove(t.id) }
        version++
    }

    // endregion

    // region —— 执行 ——

    private fun pump() {
        if (inFlightId != null) return
        val task = taskList.firstOrNull { it.state == CacheState.RUNNING } ?: return
        val runner = runners[task.id] ?: return
        val file = CacheEngine.next(task)
        if (file == null) {
            update(task.id) { it.copy(state = CacheState.DONE) }
            pump()
            return
        }
        inFlightId = task.id
        val localPath = joinPath(task.localDir, file.relPath)
        runner.io.download(runner.sessionId, file.remotePath, localPath) { success, error ->
            inFlightId = null
            val cur = taskList.firstOrNull { it.id == task.id }
            // 取消后任务已从列表移除 → 忽略迟到回调；暂停时仍记这一文件已完成，但不再继续调度
            if (cur != null && cur.state != CacheState.CANCELLED) {
                if (success) {
                    update(task.id) { CacheEngine.advance(it, file, file.size) }
                } else {
                    update(task.id) { CacheEngine.fail(it, error?.msg ?: "下载失败") }
                }
            }
            schedulePump()
        }
    }

    private fun schedulePump() {
        // 文件之间留一小段间隔：让 UI 有机会渲染，并使暂停/取消能在文件边界被观察到。
        // 用 kotlinx.coroutines（真实 setTimeout），**不要用 kuikly core 的 GlobalScope.launch+delay**
        // —— 后者依赖 currentPageId，在模块异步回调里可能不恢复（表现为只下第一个文件就停住）。
        GlobalScope.launch {
            delay(STEP_GAP_MS.toLong())
            pump()
        }
    }

    private fun update(id: String, transform: (CacheTask) -> CacheTask) {
        val idx = taskList.indexOfFirst { it.id == id }
        if (idx < 0) return
        val next = transform(taskList[idx])
        if (next != taskList[idx]) {
            taskList[idx] = next
            version++
        }
    }

    // endregion

    // region —— 工具 ——

    private fun newId(): String = "cache_" + DateTime.currentTimestamp() + "_" + (++seq)

    /** 相对根目录的路径（用于本地还原目录结构）；不依赖正则（Kotlin/JS 禁用正则，见 AGENTS） */
    private fun relPath(base: String, path: String, name: String): String {
        var rel = if (path.startsWith(base)) path.substring(base.length) else name
        rel = rel.trim('/')
        return rel.ifEmpty { name }
    }

    private fun joinLocal(root: String, name: String): String = root.trimEnd('/') + "/" + name.replace('/', '_')

    private fun joinPath(dir: String, rel: String): String = dir.trimEnd('/') + "/" + rel.trimStart('/')

    // endregion
}
