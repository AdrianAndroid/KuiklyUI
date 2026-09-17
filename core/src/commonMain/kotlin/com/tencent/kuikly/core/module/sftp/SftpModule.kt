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

import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.module.ModuleConst
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * SFTP 主 Module（§3.1 / §3.5 / §7.1 / §21.2）
 *
 * 所有方法通过 `asyncToNativeMethod` 调用原生侧 `KRSftpModule`；`read` 走原子通道传 ByteArray。
 * 错误回包统一为 `SftpError.fromJson(callback.data.optString("error"))`。
 *
 * **绑定 Page 生命周期**：每个 SFTP Page 通过 `Pager.createExternalModules()` 实例化本 Module。
 */
class SftpModule : Module() {
    override fun moduleName(): String = MODULE_NAME

    // region —— 连接管理 ——

    fun connect(param: SftpConnectParam, callback: (sessionId: String?, error: SftpError?) -> Unit) {
        asyncToNativeMethod(METHOD_CONNECT, param.toJson()) { data ->
            val err = data?.str("error")
            if (err.isNullOrEmpty()) {
                callback(data?.str("sessionId"), null)
            } else {
                callback(null, SftpError.fromJson(err))
            }
        }
    }

    fun disconnect(sessionId: String, callback: ((error: SftpError?) -> Unit)? = null) {
        val params = JSONObject()
        params.put("sessionId", sessionId)
        asyncToNativeMethod(METHOD_DISCONNECT, params) { _ ->
            callback?.invoke(null)
        }
    }

    // endregion

    // region —— 文件浏览 ——

    fun list(
        sessionId: String,
        remotePath: String,
        includeHidden: Boolean = true,
        offset: Int = 0,
        limit: Int = 10000,
        callback: (entries: List<SftpEntry>, hasMore: Boolean, error: SftpError?) -> Unit
    ) {
        val params = JSONObject()
        params.put("sessionId", sessionId)
        params.put("remotePath", remotePath)
        params.put("includeHidden", includeHidden)
        params.put("offset", offset)
        params.put("limit", limit)
        asyncToNativeMethod(METHOD_LIST, params) { data ->
            val err = data?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(emptyList(), false, SftpError.fromJson(err))
            } else {
                val arr = data?.arrOrNull("entries") ?: JSONArray()
                val hasMore = data?.bool("hasMore") ?: false
                callback(SftpEntry.listFromJson(arr), hasMore, null)
            }
        }
    }

    fun stat(
        sessionId: String,
        remotePath: String,
        followSymlink: Boolean = true,
        callback: (entry: SftpEntry?, error: SftpError?) -> Unit
    ) {
        val params = JSONObject()
        params.put("sessionId", sessionId)
        params.put("remotePath", remotePath)
        params.put("followSymlink", followSymlink)
        asyncToNativeMethod(METHOD_STAT, params) { data ->
            val err = data?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(null, SftpError.fromJson(err))
            } else {
                callback(data?.objOrNull("entry")?.let { SftpEntry.fromJson(it) }, null)
            }
        }
    }

    // endregion

    // region —— 流式读 ——

    fun openRead(sessionId: String, remotePath: String, callback: (fileHandleId: String?, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("sessionId", sessionId)
        params.put("remotePath", remotePath)
        asyncToNativeMethod(METHOD_OPEN_READ, params) { data ->
            val err = data?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(null, SftpError.fromJson(err))
            } else {
                callback(data?.str("fileHandleId"), null)
            }
        }
    }

    /**
     * 读取 [fileHandleId] 从 [offset] 开始 [length] 字节，原子 ByteArray 通道。
     * 回参为 [Any]?: 第一个元素是 JSONObject（含 error），第二个是 ByteArray
     */
    fun read(
        fileHandleId: String,
        offset: Long,
        length: Int,
        callback: (data: ByteArray?, error: SftpError?) -> Unit
    ) {
        asyncToNativeMethod(
            METHOD_READ,
            arrayOf(fileHandleId, offset, length)
        ) { result ->
            @Suppress("UNCHECKED_CAST")
            val arr = result as? Array<Any?>
            if (arr == null || arr.size < 2) {
                callback(null, SftpError.of(SftpErrorCode.PROTOCOL_ERROR))
                return@asyncToNativeMethod
            }
            val meta = arr[0] as? JSONObject
            val bytes = arr[1] as? ByteArray
            val err = meta?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(null, SftpError.fromJson(err))
            } else {
                callback(bytes, null)
            }
        }
    }

    fun close(fileHandleId: String, callback: ((error: SftpError?) -> Unit)? = null) {
        val params = JSONObject()
        params.put("fileHandleId", fileHandleId)
        asyncToNativeMethod(METHOD_CLOSE, params) { _ ->
            callback?.invoke(null)
        }
    }

    // endregion

    // region —— 下载/上传 ——

    fun download(
        sessionId: String,
        remotePath: String,
        localName: String,
        offset: Long = 0L,
        overwrite: OverwriteMode = OverwriteMode.OVERWRITE,
        callback: (progress: Float, path: String?, error: SftpError?) -> Unit
    ) {
        val params = JSONObject()
        params.put("sessionId", sessionId)
        params.put("remotePath", remotePath)
        params.put("localName", localName)
        params.put("offset", offset)
        params.put("overwrite", overwrite.name)
        asyncToNativeMethod(METHOD_DOWNLOAD, params) { data ->
            val err = data?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(1f, null, SftpError.fromJson(err))
            } else {
                val progress = data?.optDouble("progress", 1.0)?.toFloat() ?: 1f
                val path = data?.str("path")
                callback(progress, path?.takeIf { it.isNotEmpty() }, null)
            }
        }
    }

    fun upload(
        sessionId: String,
        localPath: String,
        remotePath: String,
        offset: Long = 0L,
        overwrite: OverwriteMode = OverwriteMode.OVERWRITE,
        callback: (progress: Float, success: Boolean, error: SftpError?) -> Unit
    ) {
        val params = JSONObject()
        params.put("sessionId", sessionId)
        params.put("localPath", localPath)
        params.put("remotePath", remotePath)
        params.put("offset", offset)
        params.put("overwrite", overwrite.name)
        asyncToNativeMethod(METHOD_UPLOAD, params) { data ->
            val err = data?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(1f, false, SftpError.fromJson(err))
            } else {
                val progress = data?.optDouble("progress", 1.0)?.toFloat() ?: 1f
                val success = data?.let { !it.has("success") || it.bool("success") } ?: true
                callback(progress, success, null)
            }
        }
    }

    // endregion

    // region —— 文件管理 ——

    fun mkdir(sessionId: String, remotePath: String, recursive: Boolean = true, callback: (success: Boolean, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("sessionId", sessionId)
        params.put("remotePath", remotePath)
        params.put("recursive", recursive)
        asyncToNativeMethod(METHOD_MKDIR, params) { data ->
            val err = data?.str("error")
            callback(err.isNullOrEmpty(), if (err.isNullOrEmpty()) null else SftpError.fromJson(err))
        }
    }

    fun rm(sessionId: String, remotePath: String, recursive: Boolean = false, callback: (success: Boolean, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("sessionId", sessionId)
        params.put("remotePath", remotePath)
        params.put("recursive", recursive)
        asyncToNativeMethod(METHOD_RM, params) { data ->
            val err = data?.str("error")
            callback(err.isNullOrEmpty(), if (err.isNullOrEmpty()) null else SftpError.fromJson(err))
        }
    }

    fun rename(sessionId: String, oldPath: String, newPath: String, callback: (success: Boolean, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("sessionId", sessionId)
        params.put("oldPath", oldPath)
        params.put("newPath", newPath)
        asyncToNativeMethod(METHOD_RENAME, params) { data ->
            val err = data?.str("error")
            callback(err.isNullOrEmpty(), if (err.isNullOrEmpty()) null else SftpError.fromJson(err))
        }
    }

    fun move(sessionId: String, srcPath: String, destDir: String, callback: (success: Boolean, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("sessionId", sessionId)
        params.put("srcPath", srcPath)
        params.put("destDir", destDir)
        asyncToNativeMethod(METHOD_MOVE, params) { data ->
            val err = data?.str("error")
            callback(err.isNullOrEmpty(), if (err.isNullOrEmpty()) null else SftpError.fromJson(err))
        }
    }

    fun copy(sessionId: String, srcPath: String, destPath: String, callback: (result: SftpCopyResult?, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("sessionId", sessionId)
        params.put("srcPath", srcPath)
        params.put("destPath", destPath)
        asyncToNativeMethod(METHOD_COPY, params) { data ->
            val err = data?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(null, SftpError.fromJson(err))
            } else {
                callback(data?.objOrNull("result")?.let { SftpCopyResult.fromJson(it) }
                    ?: SftpCopyResult(true, 0, 0), null)
            }
        }
    }

    fun chmod(sessionId: String, remotePath: String, mode: String, callback: (success: Boolean, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("sessionId", sessionId)
        params.put("remotePath", remotePath)
        params.put("mode", mode)
        asyncToNativeMethod(METHOD_CHMOD, params) { data ->
            val err = data?.str("error")
            callback(err.isNullOrEmpty(), if (err.isNullOrEmpty()) null else SftpError.fromJson(err))
        }
    }

    fun chown(sessionId: String, remotePath: String, uid: Int, gid: Int, callback: (success: Boolean, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("sessionId", sessionId)
        params.put("remotePath", remotePath)
        params.put("uid", uid)
        params.put("gid", gid)
        asyncToNativeMethod(METHOD_CHOWN, params) { data ->
            val err = data?.str("error")
            callback(err.isNullOrEmpty(), if (err.isNullOrEmpty()) null else SftpError.fromJson(err))
        }
    }

    fun setMtime(sessionId: String, remotePath: String, mtime: Long, atime: Long, callback: (success: Boolean, error: SftpError?) -> Unit) {
        val params = JSONObject()
        params.put("sessionId", sessionId)
        params.put("remotePath", remotePath)
        params.put("mtime", mtime)
        params.put("atime", atime)
        asyncToNativeMethod(METHOD_SETMTIME, params) { data ->
            val err = data?.str("error")
            callback(err.isNullOrEmpty(), if (err.isNullOrEmpty()) null else SftpError.fromJson(err))
        }
    }

    // endregion

    // region —— 批量任务 ——

    fun batchTask(task: SftpBatchTask, callback: (progress: Float, success: Boolean, error: SftpError?) -> Unit) {
        asyncToNativeMethod(METHOD_BATCH_TASK, task.toJson()) { data ->
            val err = data?.str("error")
            if (!err.isNullOrEmpty()) {
                callback(1f, false, SftpError.fromJson(err))
            } else {
                val progress = data?.optDouble("progress", 1.0)?.toFloat() ?: 1f
                val success = data?.let { !it.has("success") || it.bool("success") } ?: true
                callback(progress, success, null)
            }
        }
    }

    fun cancelBatchTask(taskId: String, callback: ((error: SftpError?) -> Unit)? = null) {
        val params = JSONObject()
        params.put("taskId", taskId)
        asyncToNativeMethod(METHOD_CANCEL_BATCH_TASK, params) { _ ->
            callback?.invoke(null)
        }
    }

    // endregion

    companion object {
        const val MODULE_NAME = ModuleConst.SFTP

        const val METHOD_CONNECT = "connect"
        const val METHOD_DISCONNECT = "disconnect"
        const val METHOD_LIST = "list"
        const val METHOD_STAT = "stat"
        const val METHOD_OPEN_READ = "openRead"
        const val METHOD_READ = "read"
        const val METHOD_CLOSE = "close"
        const val METHOD_DOWNLOAD = "download"
        const val METHOD_UPLOAD = "upload"
        const val METHOD_MKDIR = "mkdir"
        const val METHOD_RM = "rm"
        const val METHOD_RENAME = "rename"
        const val METHOD_MOVE = "move"
        const val METHOD_COPY = "copy"
        const val METHOD_CHMOD = "chmod"
        const val METHOD_CHOWN = "chown"
        const val METHOD_SETMTIME = "setMtime"
        const val METHOD_BATCH_TASK = "batchTask"
        const val METHOD_CANCEL_BATCH_TASK = "cancelBatchTask"
    }
}
