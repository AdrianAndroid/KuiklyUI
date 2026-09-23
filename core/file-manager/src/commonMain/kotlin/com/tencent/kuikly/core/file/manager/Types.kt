package com.tencent.kuikly.core.file.manager

/**
 * 共享契约类型（框架无关）。与 packages/file-transfer（Electron 引擎）字段对齐，
 * 通过宿主在边界处做映射。
 */
enum class TransferDirection(val wire: String) {
    Upload("upload"),
    Download("download"),
}

enum class ConflictAction(val wire: String) {
    /** 部分下载续传：远端已有更大文件，按 offset 写入 */
    Resume("resume"),
    Overwrite("overwrite"),
    Skip("skip"),
    Rename("rename"),
    Cancel("cancel"),
}

enum class TransferStatus(val wire: String) {
    Queued("queued"),
    Scanning("scanning"),
    Running("running"),
    WaitingConflict("waiting-conflict"),
    Interrupted("interrupted"),
    Done("done"),
    Error("error"),
    Canceled("canceled"),
}

enum class Pane { Local, Remote }

enum class EntryType { File, Dir, Symlink, Other }

data class BackendEntry(
    val name: String,
    val type: EntryType,
    val size: Long,
    val mtime: Long,
    val mode: Int? = null,
    val owner: Int? = null,
    val group: Int? = null,
)

/** 单文件传输项（含续传 offset）。name 用于 Rename 冲突。isDir 为 true 时整目录递归传输（由 engines 编排）。 */
data class FileItem(
    val local: String,
    val remote: String,
    val size: Long,
    val offset: Long = 0L,
    val done: Boolean = false,
    val name: String = remote.substringAfterLast('/').ifEmpty { remote.substringAfterLast('\\') },
    val isDir: Boolean = false,
)

data class TransferRequest(
    val serverId: String,
    val direction: TransferDirection,
    val localPath: String,
    val remotePath: String,
    val isDir: Boolean = false,
    val chunkSize: Int? = null,
    val overwrite: Boolean = false,
)

data class TransferTask(
    val id: String,
    val serverId: String,
    val direction: TransferDirection,
    val name: String,
    val status: TransferStatus,
    val bytesTotal: Long = 0L,
    val bytesSent: Long = 0L,
    val currentFile: String? = null,
    val error: String? = null,
    val startedAt: Long = 0L,
    val finishedAt: Long? = null,
    val retryAttempts: Int = 0,
)

data class ConflictInfo(
    val remotePath: String,
    val sizeLocal: Long,
    val sizeRemote: Long? = null,
    val mtimeLocal: Long,
    val mtimeRemote: Long? = null,
)

/** 冲突解决回调：由宿主决定。`Resume` 含义：复用 offset 续写（远端更大时用）。 */
typealias ConflictResolver = (info: ConflictInfo) -> ConflictActionAndName

data class ConflictActionAndName(
    val action: ConflictAction,
    val newName: String? = null,
)

data class TransferProgressEvent(
    val taskId: String,
    val bytesSent: Long,
    val bytesTotal: Long,
    val currentFile: String? = null,
)

data class EditorSession(
    val content: ByteArray,
    val path: String,
    val encoding: String = "utf-8",
    val isDirty: Boolean = false,
)

data class FileManagerConfig(
    val enableFileEditor: Boolean = true,
    val maxEditorBytes: Long = 2L * 1024 * 1024,
    val defaultRetryBackoffMs: List<Long> = DEFAULT_RETRY_BACKOFF_MS,
    val retryMax: Int = DEFAULT_RETRY_MAX,
    val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
) {
    companion object {
        val DEFAULT_RETRY_BACKOFF_MS: List<Long> = listOf(3_000, 5_000, 10_000, 20_000, 30_000)
        const val DEFAULT_RETRY_MAX = 5
        const val DEFAULT_MAX_CONCURRENT = 3
    }
}
