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

/**
 * 目录缓存任务（纯数据，便于单测与跨端复用）。
 *
 * 设计：**顺序缓存**（一次只跑一个任务），逐文件下载；支持暂停/继续/取消。
 * 体积超过阈值时由页面先弹确认（见 [CacheEngine.NEED_CONFIRM_BYTES]）。
 */
internal enum class CacheState { RUNNING, PAUSED, CANCELLED, DONE, FAILED }

internal data class CacheFile(val remotePath: String, val relPath: String, val size: Long)

internal data class CacheTask(
    val id: String,
    val name: String,
    /** 远端根目录 */
    val remoteDir: String,
    /** 本地根目录（宿主沙盒/本地缓存目录） */
    val localDir: String,
    val files: List<CacheFile>,
    val totalBytes: Long,
    val state: CacheState = CacheState.RUNNING,
    val index: Int = 0,
    val doneBytes: Long = 0L,
    val note: String = ""
) {
    val progress: Float
        get() = if (totalBytes <= 0L) 0f else (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

    val currentFile: CacheFile?
        get() = files.getOrNull(index)
}

/**
 * 缓存引擎：与 UI 解耦的纯逻辑。
 * - [buildTask]：由「递归列目录结果」构造任务（调用方负责拉取目录树）
 * - [next]：取下一个待下载文件；[advance] 推进进度；[pause/resume/cancel] 改变状态
 */
internal object CacheEngine {

    /** 超过该体积先让用户确认（默认 200MB） */
    const val NEED_CONFIRM_BYTES: Long = 200L * 1024L * 1024L

    fun needConfirm(totalBytes: Long): Boolean = totalBytes > NEED_CONFIRM_BYTES

    fun buildTask(
        id: String,
        name: String,
        remoteDir: String,
        localDir: String,
        files: List<CacheFile>
    ): CacheTask {
        val total = files.sumOf { if (it.size > 0) it.size else 0L }
        return CacheTask(
            id = id,
            name = name,
            remoteDir = remoteDir,
            localDir = localDir,
            files = files,
            totalBytes = total,
        )
    }

    /** 下一个待下载文件（仅 RUNNING 且未跑完时返回） */
    fun next(task: CacheTask): CacheFile? {
        if (task.state != CacheState.RUNNING) return null
        return task.files.getOrNull(task.index)
    }

    /** 单个文件完成：推进下标与已下载字节；跑完则置 DONE */
    fun advance(task: CacheTask, file: CacheFile, downloaded: Long): CacheTask {
        val idx = task.index + 1
        val done = task.doneBytes + (if (downloaded > 0) downloaded else (if (file.size > 0) file.size else 0L))
        return if (idx >= task.files.size) {
            task.copy(index = idx, doneBytes = done, state = CacheState.DONE)
        } else {
            task.copy(index = idx, doneBytes = done)
        }
    }

    fun fail(task: CacheTask, note: String): CacheTask = task.copy(state = CacheState.FAILED, note = note)

    fun pause(task: CacheTask): CacheTask =
        if (task.state == CacheState.RUNNING) task.copy(state = CacheState.PAUSED) else task

    fun resume(task: CacheTask): CacheTask =
        if (task.state == CacheState.PAUSED) task.copy(state = CacheState.RUNNING) else task

    fun cancel(task: CacheTask): CacheTask = task.copy(state = CacheState.CANCELLED)

    /** 汇总（供 UI 展示）：总任务数 / 已完成 / 正在跑 */
    fun summary(tasks: List<CacheTask>): String {
        val running = tasks.count { it.state == CacheState.RUNNING }
        val paused = tasks.count { it.state == CacheState.PAUSED }
        val done = tasks.count { it.state == CacheState.DONE }
        if (tasks.isEmpty()) return "暂无缓存任务"
        return "缓存任务 ${tasks.size} · 进行中 $running · 暂停 $paused · 完成 $done"
    }
}
