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

import com.tencent.kuikly.core.render.android.css.ktx.toJSONObjectSafely
import com.tencent.kuikly.core.render.android.css.ktx.toMap
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android SFTP 主 Module（§3.1 / §3.5 / §7.1 / §21.2）
 *
 * 通过 JSch 实现 SSH/SFTP；方法表与 commonMain [com.tencent.kuikly.core.module.sftp.SftpModule] 一一对应。
 * 所有阻塞操作在 [KuiklyRenderAdapterManager.krThreadAdapter] 子线程执行。
 */
class KRSftpModule : KuiklyRenderBaseModule() {

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            METHOD_CONNECT -> connect(params, callback)
            METHOD_DISCONNECT -> disconnect(params, callback)
            METHOD_LIST -> list(params, callback)
            METHOD_STAT -> stat(params, callback)
            METHOD_OPEN_READ -> openRead(params, callback)
            METHOD_CLOSE -> close(params, callback)
            METHOD_DOWNLOAD -> download(params, callback)
            METHOD_UPLOAD -> upload(params, callback)
            METHOD_MKDIR -> mkdir(params, callback)
            METHOD_RM -> rm(params, callback)
            METHOD_RENAME -> rename(params, callback)
            METHOD_MOVE -> move(params, callback)
            METHOD_COPY -> copy(params, callback)
            METHOD_CHMOD -> chmod(params, callback)
            METHOD_CHOWN -> chown(params, callback)
            METHOD_SETMTIME -> setMtime(params, callback)
            METHOD_BATCH_TASK -> batchTask(params, callback)
            METHOD_CANCEL_BATCH_TASK -> cancelBatchTask(params, callback)
            else -> super.call(method, params, callback)
        }
    }

    /** read 走原子 ByteArray 通道 */
    override fun call(method: String, params: Any?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            METHOD_READ -> {
                val args = params as? Array<Any?> ?: return null
                val fileHandleId = args.getOrNull(0) as? String ?: return null
                val offset = (args.getOrNull(1) as? Long) ?: (args.getOrNull(1) as? Int)?.toLong() ?: 0L
                val length = (args.getOrNull(2) as? Int) ?: 0
                read(fileHandleId, offset, length, callback)
            }
            else -> super.call(method, params, callback)
        }
    }

    private fun connect(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val json = params.toJSONObjectSafely()
                val sessionId = KRSftpClient.connect(json)
                callback?.invoke(mapOf("sessionId" to sessionId))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun disconnect(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            val sessionId = json.optString("sessionId")
            try {
                KRSftpClient.disconnect(sessionId)
                callback?.invoke(mapOf("ok" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun list(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            val sessionId = json.optString("sessionId")
            val remotePath = json.optString("remotePath")
            try {
                val entries = KRSftpClient.list(sessionId, remotePath)
                val arr = JSONArray()
                entries.forEach { arr.put(it.toJson()) }
                val result = JSONObject()
                result.put("entries", arr)
                result.put("hasMore", false)
                callback?.invoke(result.toMap())
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun stat(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            val sessionId = json.optString("sessionId")
            val remotePath = json.optString("remotePath")
            val followSymlink = json.optBoolean("followSymlink", true)
            try {
                val entry = KRSftpClient.stat(sessionId, remotePath, followSymlink)
                callback?.invoke(mapOf("entry" to entry.toJson().toMap()))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun openRead(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            val sessionId = json.optString("sessionId")
            val remotePath = json.optString("remotePath")
            try {
                val fileHandleId = KRSftpClient.openRead(sessionId, remotePath)
                callback?.invoke(mapOf("fileHandleId" to fileHandleId))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun read(fileHandleId: String, offset: Long, length: Int, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val bytes = KRSftpClient.read(fileHandleId, offset, length)
                // 原子通道回包：[meta, ByteArray]
                callback?.invoke(arrayOf(JSONObject().apply { put("ok", true) }, bytes))
            } catch (e: Exception) {
                val meta = JSONObject().apply {
                    put("error", SftpErrorFormatter.format(e))
                }
                callback?.invoke(arrayOf(meta, null))
            }
        }
    }

    private fun close(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            val fileHandleId = json.optString("fileHandleId")
            try {
                KRSftpClient.close(fileHandleId)
                callback?.invoke(mapOf("ok" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun download(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            try {
                json.put("cacheDir", cacheDir())
                val localPath = KRSftpClient.download(json)
                callback?.invoke(mapOf("progress" to 1.0f, "path" to localPath, "success" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun upload(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            try {
                val progress = KRSftpClient.upload(json)
                callback?.invoke(mapOf("progress" to progress, "success" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun mkdir(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            try {
                KRSftpClient.mkdir(json)
                callback?.invoke(mapOf("ok" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun rm(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            try {
                KRSftpClient.rm(json)
                callback?.invoke(mapOf("ok" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun rename(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            try {
                KRSftpClient.rename(json)
                callback?.invoke(mapOf("ok" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun move(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            try {
                KRSftpClient.move(json)
                callback?.invoke(mapOf("ok" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun copy(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            json.put("cacheDir", cacheDir())
            try {
                val result = KRSftpClient.copy(json)
                // 裸 JSONObject 过不了桥（Kotlin 侧会读不到字段），统一 JSONObject + toMap()
                val payload = JSONObject()
                payload.put("result", result)
                payload.put("ok", true)
                callback?.invoke(payload.toMap())
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun chmod(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            try {
                KRSftpClient.chmod(json)
                callback?.invoke(mapOf("ok" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun chown(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            try {
                KRSftpClient.chown(json)
                callback?.invoke(mapOf("ok" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun setMtime(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            try {
                KRSftpClient.setMtime(json)
                callback?.invoke(mapOf("ok" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun batchTask(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            try {
                val json = params.toJSONObjectSafely()
                json.put("cacheDir", cacheDir())
                val progress = KRSftpClient.batchTask(json)
                callback?.invoke(mapOf("progress" to progress, "success" to true))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to SftpErrorFormatter.format(e)))
            }
        }
    }

    private fun cancelBatchTask(params: String?, callback: KuiklyRenderCallback?) {
        executeOnSubThread {
            val json = params.toJSONObjectSafely()
            val taskId = json.optString("taskId")
            KRSftpClient.cancelBatchTask(taskId)
            callback?.invoke(mapOf("ok" to true))
        }
    }

    /** 本地缓存目录（下载/复制的临时文件落地处），由宿主 Context 提供 */
    private fun cacheDir(): String =
        (context?.cacheDir ?: java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp")).absolutePath

    private fun executeOnSubThread(block: () -> Unit) {
        com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
            .krThreadAdapter?.executeOnSubThread(block) ?: Thread(block).start()
    }

    companion object {
        const val MODULE_NAME = "KRSftpModule"
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
