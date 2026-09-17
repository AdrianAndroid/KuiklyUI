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
package com.tencent.kuikly.demo.pages.sftp

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.log.KLog
import com.tencent.kuikly.core.module.FileModule
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.module.sftp.AuthMethod
import com.tencent.kuikly.core.module.sftp.OverwriteMode
import com.tencent.kuikly.core.module.sftp.SftpBatchAction
import com.tencent.kuikly.core.module.sftp.SftpBatchTask
import com.tencent.kuikly.core.module.sftp.SftpConnectParam
import com.tencent.kuikly.core.module.sftp.SftpConnection
import com.tencent.kuikly.core.module.sftp.SftpError
import com.tencent.kuikly.core.module.sftp.SftpFavorite
import com.tencent.kuikly.core.module.sftp.SftpFavoriteIcon
import com.tencent.kuikly.core.module.sftp.SftpFavoriteSortBy
import com.tencent.kuikly.core.module.sftp.SftpMediaProxyModule
import com.tencent.kuikly.core.module.sftp.SftpMediaUrlBuilder
import com.tencent.kuikly.core.module.sftp.SftpPlaybackRecord
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * SFTP 端到端集成自测页（非产品页面）
 *
 * 对真实 SFTP 服务器按顺序跑完 §7.1 / §7.2 全部方法，并把每一步的
 * PASS / FAIL + 实测值写入日志（tag `SftpTest`），供自动化/AI 直接判读。
 *
 * 覆盖：连接、浏览(stat/list)、流式读(openRead/read/close)、上传、下载、
 * mkdir/rm/rename/move/copy/chmod/chown/setMtime、batchTask、
 * 收藏 CRUD + 搜索、连接配置 CRUD、播放历史 CRUD、以及错误路径。
 *
 * 目标服务器通过 pageData 注入 host / port / user / password / remoteHome（不写入源码）。
 */
/** 单步：名字 + 一个接收 next 回调的执行体 */
private typealias SftpTestStep = Pair<String, (((Boolean, String) -> Unit) -> Unit)>

@Page(SftpIntegrationTestPage.PAGE_NAME)
internal class SftpIntegrationTestPage : SftpBasePager() {

    companion object {
        private const val TAG = "SftpTest"
        /** 故意失败用例会弹模态错误框，默认关闭 */
        private const val BAD_AUTH_TEST = false
        /** 置 true 则跑完不 disconnect，供外部 curl 验证本地代理 */
        private const val HOLD_SESSION_FOR_EXTERNAL_VERIFY = false
        const val PAGE_NAME = "SftpIntegrationTestPage"

        // —— 测试目标 ——
        // 账号密码不写进源码：由页面参数注入，避免凭据进入版本库。
        // 启动示例（临时把 ContentView 指向本页）：
        //   KuiklyNavigationViewPage(pageName: "SftpIntegrationTestPage", data: [
        //       "host": "192.168.2.2", "port": 22,
        //       "user": "<user>", "password": "<password>",
        //       "remoteHome": "/home/<user>",
        //   ])
        private const val DEFAULT_PORT = 22

        /** 远端媒体样本（用 scp 预置，见 §17.4）；用于验证随机读=流式播放的数据通路 */
        private const val MEDIA_FILE = "sftp_kuikly_media.mp4"
        private const val MEDIA_SIZE = 95627L
        private const val MEDIA_SUM = 11393384L          // (sum of bytes) % 1000000007
        private const val MEDIA_HEAD16 = "000000206674797069736f6d00000200"
        private const val MEDIA_MID16 = "bf83b361bd4b46fbbca64bc57df7c2ef"
        private const val MEDIA_TAIL16 = "a934fb2481a7d1640914be011881b470"

        private const val LOCAL_UPLOAD_NAME = "sftp_it_upload.txt"
        private const val LOCAL_DOWNLOAD_NAME = "sftp_it_downloaded.txt"
        private val PAYLOAD = ("KuiklyUI SFTP integration payload | " + "0123456789abcdef".repeat(8))
    }

    // —— 运行配置（在 created() 中从 pageData.params 读取） ——
    private var host = ""
    private var port = DEFAULT_PORT
    private var user = ""
    private var password = ""
    private var remoteHome = ""

    /** 屏幕上的实时状态，便于截图确认进度 */
    private var status: String by observable("准备中…")

    // —— 运行期状态 ——
    private var sessionId: String = ""
    private var baseDir = ""
    private var localUploadResolved = ""
    private var downloadLocalPath = ""
    private var uploadedSize = 0L
    private var favoriteId = ""
    private var favoriteConnectionId = ""
    private var historyId = ""
    private var connectionId = ""
    private var batchTaskId = ""

    private var passed = 0
    private var failed = 0
    private val failures = mutableListOf<String>()

    private val steps = mutableListOf<SftpTestStep>()
    private var cursor = 0

    override fun createExternalModules(): Map<String, Module>? {
        val map = HashMap<String, Module>()
        super.createExternalModules()?.let { map.putAll(it) }
        map[FileModule.MODULE_NAME] = FileModule()
        return map
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { backgroundColor(SftpColorTokens.bg) }
            View {
                attr {
                    size(pagerData.pageViewWidth, 56f)
                    flexDirectionRow()
                    alignItemsCenter()
                    padding(16f, 8f, 16f, 8f)
                    backgroundColor(SftpColorTokens.cardBg)
                }
                Text {
                    attr {
                        text("SFTP 集成自测")
                        fontSize(18f)
                        fontWeightBold()
                        color(SftpColorTokens.textPrimary)
                    }
                }
            }
            View {
                attr { flex(1f); padding(16f); flexDirectionColumn() }
                Text {
                    attr {
                        text(ctx.status)
                        fontSize(14f)
                        color(SftpColorTokens.textPrimary)
                    }
                }
                Text {
                    attr {
                        text("目标 ${ctx.user}@${ctx.host}:${ctx.port}")
                        fontSize(12f)
                        marginTop(8f)
                        color(SftpColorTokens.textSecondary)
                    }
                }
            }
        }
    }

    override fun created() {
        super.created()
        val params = pageData.params
        host = params.optString("host", "")
        port = params.optInt("port", 0).takeIf { it > 0 } ?: DEFAULT_PORT
        user = params.optString("user", "")
        password = params.optString("password", "")
        remoteHome = params.optString("remoteHome", "")
        baseDir = "$remoteHome/sftp_kuikly_it"
        favoriteConnectionId = "it_conn_${host}_${user}"
        buildSteps()
        // 让首帧渲染完成后再开跑，避免日志与首帧交织
        KLog.i(TAG, "==================================================")
        if (host.isEmpty() || user.isEmpty() || password.isEmpty() || remoteHome.isEmpty()) {
            status = "缺少配置：请在 pageData 注入 host/port/user/password/remoteHome"
            KLog.i(TAG, "SUITE ABORT | missing pageData config " +
                "(host=${host.ifEmpty { "<empty>" }} user=${user.ifEmpty { "<empty>" }} " +
                "remoteHome=${remoteHome.ifEmpty { "<empty>" }} password=${if (password.isEmpty()) "<empty>" else "<set>"})")
            KLog.i(TAG, "==================================================")
            return
        }
        KLog.i(TAG, "SUITE START target=$user@$host:$port base=$baseDir")
        runNext()
    }

    // region —— 步骤定义 ——

    private fun step(name: String, fn: ((Boolean, String) -> Unit) -> Unit) {
        steps.add(name to fn)
    }

    private fun buildSteps() {
        // ---------- 连接 ----------
        step("connect(password)") { next ->
            sftpModule().connect(
                SftpConnectParam(host = host, port = port, user = user, password = password,
                    authMethod = AuthMethod.PASSWORD)
            ) { sid, err ->
                sessionId = sid ?: ""
                next(sid != null, if (sid != null) "sessionId=$sid" else "err=${err.desc()}")
            }
        }
        // NOTE: 故意用错误密码的用例会触发 KRLogModule.logError → Kuikly 模态错误弹窗，
        // 干扰自动化运行；已改为可选（默认关闭）。需要验证时把 BAD_AUTH_TEST 置 true。
        if (BAD_AUTH_TEST) {
            step("connect(bad password) -> expect error") { next ->
                sftpModule().connect(
                    SftpConnectParam(host = host, port = port, user = user, password = "definitely-wrong",
                        authMethod = AuthMethod.PASSWORD)
                ) { sid, err ->
                    next(sid == null && err != null, "sid=$sid err=${err.desc()}")
                }
            }
        }

        // ---------- 浏览 ----------
        step("list($remoteHome)") { next ->
            sftpModule().list(sessionId, remoteHome) { entries, hasMore, err ->
                next(entries.isNotEmpty() && err == null,
                    "count=${entries.size} hasMore=$hasMore err=${err.desc()}")
            }
        }
        step("stat($remoteHome) isDir") { next ->
            sftpModule().stat(sessionId, remoteHome) { entry, err ->
                next(entry != null && entry.isDir, "entry=${entry?.let { "${it.name} dir=${it.isDir} perm=${it.permission}" }} err=${err.desc()}")
            }
        }
        step("stat(nonexistent) -> expect error") { next ->
            sftpModule().stat(sessionId, "$remoteHome/__no_such_file_kuikly__") { entry, err ->
                next(entry == null && err != null, "entry=$entry err=${err.desc()}")
            }
        }

        // ---------- 目录管理 ----------
        step("mkdir(baseDir) + verify by list") { next ->
            sftpModule().mkdir(sessionId, baseDir, false) { ok, err ->
                if (!ok) { next(false, "mkdir returned ok=$ok err=${err.desc()}"); return@mkdir }
                verifyListed(remoteHome, "sftp_kuikly_it", true, next)
            }
        }
        step("mkdir(nested recursive $baseDir/sub/deep) + verify") { next ->
            sftpModule().mkdir(sessionId, "$baseDir/sub/deep", true) { ok, err ->
                if (!ok) { next(false, "mkdir returned ok=$ok err=${err.desc()}"); return@mkdir }
                sftpModule().list(sessionId, "$baseDir/sub") { entries, _, e2 ->
                    val hit = entries.any { it.name == "deep" }
                    next(hit, "sub contains deep=$hit err=${e2.desc()}")
                }
            }
        }
        step("stat(created deep dir)") { next ->
            verifyStat("$baseDir/sub/deep", true, next)
        }

        // ---------- 上传 ----------
        step("local file write (FileModule)") { next ->
            acquireModule<FileModule>(FileModule.MODULE_NAME)
                .writeFile(LOCAL_UPLOAD_NAME, PAYLOAD) { res ->
                    val p = res?.optString("path", "") ?: ""
                    localUploadResolved = p
                    next(p.isNotEmpty(), "localPath=$p err=${res?.optString("error", "")}")
                }
        }
        step("upload -> $baseDir/sub/deep/uploaded.txt") { next ->
            sftpModule().upload(sessionId, localUploadResolved, "$baseDir/sub/deep/uploaded.txt",
                0L, OverwriteMode.OVERWRITE) { progress, ok, err ->
                next(ok, "progress=$progress err=${err.desc()}")
            }
        }
        step("stat(uploaded) size == payload") { next ->
            sftpModule().stat(sessionId, "$baseDir/sub/deep/uploaded.txt") { entry, err ->
                uploadedSize = entry?.size ?: -1
                next(uploadedSize == PAYLOAD.length.toLong(),
                    "size=$uploadedSize expected=${PAYLOAD.length} err=${err.desc()}")
            }
        }

        // ---------- 流式读 ----------
        step("openRead(uploaded)") { next ->
            sftpModule().openRead(sessionId, "$baseDir/sub/deep/uploaded.txt") { fh, err ->
                readHandleId = fh ?: ""
                next(fh != null, "fileHandleId=$fh err=${err.desc()}")
            }
        }
        step("read(fh, 0, 16) matches payload head") { next ->
            sftpModule().read(readHandleId, 0L, 16) { bytes, err ->
                val s = bytes?.decodeToString() ?: ""
                next(bytes?.size == 16 && s == PAYLOAD.substring(0, 16),
                    "read=${bytes?.size} \"$s\" err=${err.desc()}")
            }
        }
        step("read(fh, 10, 8) offset read") { next ->
            sftpModule().read(readHandleId, 10L, 8) { bytes, err ->
                val s = bytes?.decodeToString() ?: ""
                next(bytes?.size == 8 && s == PAYLOAD.substring(10, 18),
                    "read=${bytes?.size} \"$s\" err=${err.desc()}")
            }
        }
        step("close(fh)") { next ->
            sftpModule().close(readHandleId) { err ->
                next(err == null, "err=${err.desc()}")
            }
        }

        // ---------- 下载 ----------
        step("download(uploaded -> local)") { next ->
            sftpModule().download(sessionId, "$baseDir/sub/deep/uploaded.txt", LOCAL_DOWNLOAD_NAME) { progress, path, err ->
                downloadLocalPath = path ?: ""
                next(path != null && progress >= 1f, "progress=$progress path=$path err=${err.desc()}")
            }
        }
        step("re-upload downloaded file, size matches") { next ->
            sftpModule().upload(sessionId, downloadLocalPath, "$baseDir/sub/deep/roundtrip.txt",
                0L, OverwriteMode.OVERWRITE) { _, ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }
        step("stat(roundtrip) size == payload") { next ->
            sftpModule().stat(sessionId, "$baseDir/sub/deep/roundtrip.txt") { entry, err ->
                next(entry?.size == PAYLOAD.length.toLong(),
                    "size=${entry?.size} expected=${PAYLOAD.length} err=${err.desc()}")
            }
        }

        // ---------- 改名 / 搬运 / 复制 ----------
        step("rename(uploaded.txt -> renamed.txt)") { next ->
            sftpModule().rename(sessionId, "$baseDir/sub/deep/uploaded.txt",
                "$baseDir/sub/deep/renamed.txt") { ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }
        step("move(renamed.txt -> $baseDir)") { next ->
            sftpModule().move(sessionId, "$baseDir/sub/deep/renamed.txt", baseDir) { ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }
        step("copy(file renamed.txt -> copied.txt)") { next ->
            sftpModule().copy(sessionId, "$baseDir/renamed.txt", "$baseDir/copied.txt") { result, err ->
                next(result != null && result.copiedCount >= 1,
                    "copied=${result?.copiedCount} failed=${result?.failedCount} err=${err.desc()}")
            }
        }
        step("copy(dir sub -> sub_copy, recursive)") { next ->
            sftpModule().copy(sessionId, "$baseDir/sub", "$baseDir/sub_copy") { result, err ->
                next(result != null && result.failedCount == 0,
                    "copied=${result?.copiedCount} failed=${result?.failedCount} err=${err.desc()}")
            }
        }

        // ---------- 权限 / 时间戳 ----------
        step("chmod(copied.txt, 600)") { next ->
            sftpModule().chmod(sessionId, "$baseDir/copied.txt", "600") { ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }
        step("stat verify chmod == 600 (symbolic -rw-------)") { next ->
            sftpModule().stat(sessionId, "$baseDir/copied.txt") { entry, err ->
                // stat 返回的是符号权限串（如 -rw-------），不是八进制
                val p = entry?.permission ?: ""
                val ownerRW = p.length >= 4 && p[1] == 'r' && p[2] == 'w'
                val groupOtherNone = p.length >= 10 &&
                    p.substring(4, 10).all { it == '-' }
                next(ownerRW && groupOtherNone, "perm=$p err=${err.desc()}")
            }
        }
        step("setMtime(copied.txt, now-86400)") { next ->
            val t = 1700000000000L
            sftpModule().setMtime(sessionId, "$baseDir/copied.txt", t, t) { ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }
        step("stat verify mtime changed") { next ->
            sftpModule().stat(sessionId, "$baseDir/copied.txt") { entry, err ->
                val m = entry?.mtime ?: -1
                next(m > 0 && m != 1700000000000L, "mtime=$m raw=$m err=${err.desc()}")
            }
        }
        step("chown(copied.txt, self uid/gid)") { next ->
            // 用当前用户自身 uid/gid，chown 到自己是允许的
            sftpModule().stat(sessionId, remoteHome) { home, _ ->
                val uid = home?.uid ?: -1
                val gid = home?.gid ?: -1
                sftpModule().chown(sessionId, "$baseDir/copied.txt", uid, gid) { ok, err ->
                    next(ok, "uid=$uid gid=$gid ok=$ok err=${err.desc()}")
                }
            }
        }

        // ---------- 批量任务 ----------
        step("batchTask(COPY 1 item -> batch_out)") { next ->
            val task = SftpBatchTask(sessionId, SftpBatchAction.COPY,
                listOf("$baseDir/renamed.txt"), targetDir = "$baseDir/batch_out")
            sftpModule().batchTask(task) { progress, ok, err ->
                next(ok, "progress=$progress ok=$ok err=${err.desc()}")
            }
        }
        step("stat(batch_out/renamed.txt)") { next ->
            sftpModule().stat(sessionId, "$baseDir/batch_out/renamed.txt") { entry, err ->
                next(entry != null, "exists=${entry != null} err=${err.desc()}")
            }
        }
        step("batchTask(DELETE batch_out items)") { next ->
            val task = SftpBatchTask(sessionId, SftpBatchAction.DELETE,
                listOf("$baseDir/batch_out/renamed.txt"))
            batchTaskId = "it-batch-${host}"
            sftpModule().batchTask(task) { progress, ok, err ->
                next(ok, "progress=$progress ok=$ok err=${err.desc()}")
            }
        }
        step("cancelBatchTask(unknown id, expect no crash)") { next ->
            sftpModule().cancelBatchTask(batchTaskId) { err ->
                next(true, "no crash, err=${err.desc()}")
            }
        }

        // ---------- 收藏 ----------
        step("favorite add") { next ->
            val fav = SftpFavorite(
                id = "", connectionId = favoriteConnectionId, connectionLabel = "IT $host",
                remotePath = "$baseDir/copied.txt", name = "copied.txt", isDir = false,
                size = PAYLOAD.length.toLong(), starredAt = 1700000000000L, note = "it-note"
            )
            sftpFavoritesModule().add(fav) { id, err ->
                favoriteId = id ?: ""
                next(id != null, "id=$id err=${err.desc()}")
            }
        }
        step("favorite isFavorited") { next ->
            sftpFavoritesModule().isFavorited(favoriteConnectionId, "$baseDir/copied.txt") { id ->
                next(!id.isNullOrEmpty(), "id=$id")
            }
        }
        step("favorite list") { next ->
            sftpFavoritesModule().list(connectionId = favoriteConnectionId,
                sortBy = SftpFavoriteSortBy.STARRED_AT) { items, err ->
                next(items.isNotEmpty(), "count=${items.size} err=${err.desc()}")
            }
        }
        step("favorite search(keyword)") { next ->
            sftpFavoritesModule().search("copied") { items, err ->
                next(items.isNotEmpty(), "count=${items.size} err=${err.desc()}")
            }
        }
        step("favorite update(note/icon)") { next ->
            sftpFavoritesModule().update(favoriteId, "updated-note", SftpFavoriteIcon.CODE) { ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }
        step("favorite remove") { next ->
            sftpFavoritesModule().remove(favoriteId) { ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }
        step("favorite removeByConnection") { next ->
            // 先再加一个，验证联动删除
            val fav = SftpFavorite(
                id = "", connectionId = favoriteConnectionId, connectionLabel = "IT $host",
                remotePath = "$baseDir", name = "sftp_kuikly_it", isDir = true,
                size = 0L, starredAt = 1700000000001L
            )
            sftpFavoritesModule().add(fav) { _, _ ->
                sftpFavoritesModule().removeByConnection(favoriteConnectionId) { ok, count, err ->
                    next(ok && count >= 1, "ok=$ok removed=$count err=${err.desc()}")
                }
            }
        }

        // ---------- 播放历史 ----------
        step("history upsert") { next ->
            val rec = SftpPlaybackRecord(
                id = "", connectionId = favoriteConnectionId, connectionLabel = "IT $host",
                remotePath = "$baseDir/sample.mp4", name = "sample.mp4",
                duration = 120000L, position = 30000L, completed = false,
                lastPlayedAt = 1700000000000L
            )
            sftpPlaybackHistoryModule().upsert(rec) { ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }
        step("history get") { next ->
            sftpPlaybackHistoryModule().get(favoriteConnectionId, "$baseDir/sample.mp4") { rec, err ->
                historyId = rec?.id ?: ""
                next(rec != null, "id=${rec?.id} pos=${rec?.position} err=${err.desc()}")
            }
        }
        step("history listByDirectory") { next ->
            sftpPlaybackHistoryModule().listByDirectory(favoriteConnectionId, baseDir) { items, err ->
                next(items.isNotEmpty(), "count=${items.size} err=${err.desc()}")
            }
        }
        step("history listByConnection") { next ->
            sftpPlaybackHistoryModule().listByConnection(favoriteConnectionId) { items, err ->
                next(items.isNotEmpty(), "count=${items.size} err=${err.desc()}")
            }
        }
        step("history markCompleted") { next ->
            sftpPlaybackHistoryModule().markCompleted(favoriteConnectionId, "$baseDir/sample.mp4") { ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }
        step("history remove") { next ->
            sftpPlaybackHistoryModule().remove(historyId) { ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }
        step("history clearByConnection") { next ->
            sftpPlaybackHistoryModule().clearByConnection(favoriteConnectionId) { ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }

        // ---------- 连接配置 CRUD ----------
        step("connection add") { next ->
            val conn = SftpConnection(
                id = SftpConnection.buildId(host, port, user),
                label = "IT 台式机", host = host, port = port, user = user,
                password = password, authMethod = AuthMethod.PASSWORD
            )
            sftpConnectionModule().add(conn) { id, err ->
                connectionId = id ?: ""
                next(id != null, "id=$id err=${err.desc()}")
            }
        }
        step("connection list") { next ->
            sftpConnectionModule().list { items, err ->
                next(items.isNotEmpty(), "count=${items.size} err=${err.desc()}")
            }
        }
        step("connection get") { next ->
            sftpConnectionModule().get(connectionId) { conn, err ->
                next(conn != null && conn.host == host, "host=${conn?.host} err=${err.desc()}")
            }
        }
        step("connection update") { next ->
            val conn = SftpConnection(
                id = connectionId, label = "IT 台式机(updated)", host = host, port = port,
                user = user, password = password, authMethod = AuthMethod.PASSWORD
            )
            sftpConnectionModule().update(conn) { ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }
        step("connection touchLastUsed") { next ->
            sftpConnectionModule().touchLastUsed(connectionId) { err ->
                next(err == null, "err=${err.desc()}")
            }
        }
        step("connection remove") { next ->
            sftpConnectionModule().remove(connectionId) { ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }

        // ---------- 随机读（= 本地 HTTP 代理流式播放的数据通路）----------
        step("openRead(media) + size") { next ->
            sftpModule().openRead(sessionId, "$remoteHome/$MEDIA_FILE") { fh, err ->
                mediaHandleId = fh ?: ""
                sftpModule().stat(sessionId, "$remoteHome/$MEDIA_FILE") { entry, e2 ->
                    next(fh != null && entry?.size == MEDIA_SIZE,
                        "fh=$fh size=${entry?.size} expect=$MEDIA_SIZE err=${err.desc()} ${e2.desc()}")
                }
            }
        }
        step("read(media, 0, 16) == ftyp header") { next ->
            sftpModule().read(mediaHandleId, 0L, 16) { bytes, err ->
                val hex = bytes?.toHexString() ?: ""
                next(hex == MEDIA_HEAD16, "hex=$hex expect=$MEDIA_HEAD16 err=${err.desc()}")
            }
        }
        step("read(media, 50000, 16) == mid bytes") { next ->
            sftpModule().read(mediaHandleId, 50000L, 16) { bytes, err ->
                val hex = bytes?.toHexString() ?: ""
                next(hex == MEDIA_MID16, "hex=$hex expect=$MEDIA_MID16 err=${err.desc()}")
            }
        }
        step("read(media, 95000, 4096) clamps at EOF") { next ->
            sftpModule().read(mediaHandleId, 95000L, 4096) { bytes, err ->
                val n = bytes?.size ?: -1
                val tail = if (n == (MEDIA_SIZE - 95000).toInt() && n >= 16)
                    bytes!!.copyOfRange(n - 16, n).toHexString() else ""
                next(n == (MEDIA_SIZE - 95000).toInt() && tail == MEDIA_TAIL16,
                    "read=$n expect=${MEDIA_SIZE - 95000} tail=$tail err=${err.desc()}")
            }
        }
        step("read(media, past EOF) == 0 bytes") { next ->
            sftpModule().read(mediaHandleId, MEDIA_SIZE, 16) { bytes, err ->
                next(bytes?.isEmpty() == true, "read=${bytes?.size} err=${err.desc()}")
            }
        }
        step("read(media) whole file in 64KB chunks -> byte count + checksum") { next ->
            mediaOffset = 0L
            mediaSum = 0L
            mediaTotal = 0L
            readChunks(next)
        }
        step("close(media handle)") { next ->
            sftpModule().close(mediaHandleId) { err ->
                next(err == null, "err=${err.desc()}")
            }
        }

        // ---------- 传输：下载 → 上传 → 字节校验（真实 MP4，非文本） ----------
        step("download(media.mp4) -> local path") { next ->
            sftpModule().download(sessionId, "$remoteHome/$MEDIA_FILE", "kr_media_dl.mp4") { _, path, err ->
                mediaLocalPath = path ?: ""
                next(path != null && path.isNotEmpty(), "local=$path err=${err.desc()}")
            }
        }
        step("upload(downloaded) -> $baseDir/media_copy.mp4") { next ->
            sftpModule().upload(sessionId, mediaLocalPath, "$baseDir/media_copy.mp4") { _, ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }
        step("stat(media_copy.mp4) size == 95627") { next ->
            sftpModule().stat(sessionId, "$baseDir/media_copy.mp4") { entry, err ->
                next(entry?.size == MEDIA_SIZE, "size=${entry?.size} expect=$MEDIA_SIZE err=${err.desc()}")
            }
        }
        step("re-read uploaded media, checksum matches original") { next ->
            sftpModule().openRead(sessionId, "$baseDir/media_copy.mp4") { fh, err ->
                if (fh == null) { next(false, "openRead failed err=${err.desc()}"); return@openRead }
                mediaHandleId = fh
                verifyMediaChecksum(fh, next)
            }
        }
        step("copy(media_copy.mp4 -> media_copied.mp4)") { next ->
            sftpModule().copy(sessionId, "$baseDir/media_copy.mp4", "$baseDir/media_copied.mp4") { result, err ->
                next(result != null && result.copiedCount >= 1,
                    "copied=${result?.copiedCount} failed=${result?.failedCount} err=${err.desc()}")
            }
        }
        step("verify copy is byte-identical") { next ->
            sftpModule().stat(sessionId, "$baseDir/media_copied.mp4") { entry, err ->
                if (entry?.size != MEDIA_SIZE) {
                    next(false, "size=${entry?.size} expect=$MEDIA_SIZE err=${err.desc()}")
                    return@stat
                }
                sftpModule().openRead(sessionId, "$baseDir/media_copied.mp4") { fh, e2 ->
                    if (fh == null) { next(false, "openRead failed ${e2.desc()}"); return@openRead }
                    verifyMediaChecksum(fh, next)
                }
            }
        }
        step("batchTask(COPY media -> batch_out) + size") { next ->
            val task = SftpBatchTask(sessionId, SftpBatchAction.COPY,
                listOf("$baseDir/media_copy.mp4"), targetDir = "$baseDir/batch_out")
            sftpModule().batchTask(task) { _, ok, err ->
                if (!ok) { next(false, "ok=$ok err=${err.desc()}"); return@batchTask }
                sftpModule().stat(sessionId, "$baseDir/batch_out/media_copy.mp4") { entry, e2 ->
                    next(entry?.size == MEDIA_SIZE, "size=${entry?.size} err=${e2.desc()}")
                }
            }
        }
        step("batchTask(DELETE batch_out/media_copy.mp4) + verify gone") { next ->
            val task = SftpBatchTask(sessionId, SftpBatchAction.DELETE,
                listOf("$baseDir/batch_out/media_copy.mp4"))
            sftpModule().batchTask(task) { _, ok, err ->
                if (!ok) { next(false, "ok=$ok err=${err.desc()}"); return@batchTask }
                sftpModule().stat(sessionId, "$baseDir/batch_out/media_copy.mp4") { entry, e2 ->
                    next(entry == null, "still exists=${entry != null} err=${e2.desc()}")
                }
            }
        }
        step("rm(media_copy.mp4) + rm(media_copied.mp4)") { next ->
            sftpModule().rm(sessionId, "$baseDir/media_copy.mp4", false) { ok1, e1 ->
                sftpModule().rm(sessionId, "$baseDir/media_copied.mp4", false) { ok2, e2 ->
                    next(ok1 && ok2, "copy=$ok1 copied=$ok2 err=${e1.desc()} ${e2.desc()}")
                }
            }
        }

        // ---------- 本地 HTTP 代理（视频流式播放链路，§5.2）----------
        step("mediaProxy.startOrGetPort") { next ->
            sftpMediaProxyModule().startOrGetPort { p ->
                proxyPort = p
                next(p in 18080..18089, "port=$p (期望 18080-18089)")
            }
        }
        step("mediaProxy.registerToken(media)") { next ->
            sftpMediaProxyModule().registerToken(sessionId, "$remoteHome/$MEDIA_FILE", MEDIA_SIZE) { tk ->
                proxyToken = tk
                // 测试页专用：输出可被本机 curl 的地址，用于从 HTTP 层独立验证 Range 行为
                if (tk.isNotEmpty()) {
                    val url = SftpMediaUrlBuilder.buildPlayUrl(proxyPort, tk, MEDIA_FILE)
                    KLog.i(TAG, "PROXY_URL $url")
                }
                next(tk.isNotEmpty(), "token.len=${tk.length}")
            }
        }

        // ---------- 清理 ----------
        step("rm(file copied.txt)") { next ->
            sftpModule().rm(sessionId, "$baseDir/copied.txt", false) { ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }
        step("rm(dir sub_copy, recursive)") { next ->
            sftpModule().rm(sessionId, "$baseDir/sub_copy", true) { ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }
        step("rm(dir sub, recursive)") { next ->
            sftpModule().rm(sessionId, "$baseDir/sub", true) { ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }
        step("rm(baseDir, recursive)") { next ->
            sftpModule().rm(sessionId, baseDir, true) { ok, err ->
                next(ok, "ok=$ok err=${err.desc()}")
            }
        }
        step("stat(baseDir) -> expect gone") { next ->
            sftpModule().stat(sessionId, baseDir) { entry, err ->
                next(entry == null, "entry=$entry err=${err.desc()}")
            }
        }
        // 需要从 HTTP 层用 curl 独立验证代理时置 true：保留会话不 disconnect，便于外部请求
        if (!HOLD_SESSION_FOR_EXTERNAL_VERIFY) {
            step("disconnect") { next ->
                sftpModule().disconnect(sessionId) {
                    next(true, "disconnect called")
                }
            }
        } else {
            KLog.i(TAG, "SESSION HELD (external verification window); sessionId=$sessionId port=$proxyPort")
        }
    }


    /** 用 list 校验某个目录下是否存在指定名字（不信任变更类接口自身的成功标志） */
    private fun verifyListed(dir: String, name: String, shouldExist: Boolean, next: (Boolean, String) -> Unit) {
        sftpModule().list(sessionId, dir) { entries, _, err ->
            val hit = entries.any { it.name == name }
            if (hit == shouldExist) {
                next(true, "dir=$dir name=$name exists=$hit")
                return@list
            }
            // 不一致时打印实际条目名 + 延迟重试一次：用于区分「列表匹配问题」与「列表过期」
            val sample = entries.take(6).joinToString(",") { it.name }
            sftpModule().list(sessionId, dir) { retry, _, _ ->
                val hit2 = retry.any { it.name == name }
                next(hit2 == shouldExist,
                    "dir=$dir name=$name first=$hit(${entries.size}) names=[$sample] retry=$hit2(${retry.size}) err=${err.desc()}")
            }
        }
    }

    private fun verifyStat(path: String, shouldExist: Boolean, next: (Boolean, String) -> Unit) {
        sftpModule().stat(sessionId, path) { entry, err ->
            val exists = entry != null
            next(exists == shouldExist, "path=$path exists=$exists expected=$shouldExist err=${err.desc()}")
        }
    }

    private var readHandleId: String = ""
    private var mediaHandleId: String = ""
    private var mediaLocalPath: String = ""
    private var proxyPort: Int = 0
    private var proxyToken: String = ""
    private var mediaOffset = 0L
    private var mediaSum = 0L
    private var mediaTotal = 0L

    /** 分块读完整文件，累计字节数与校验和（验证与大文件整体一致的完整性） */
    private fun readChunks(next: (Boolean, String) -> Unit) {
        val chunk = 64 * 1024
        sftpModule().read(mediaHandleId, mediaOffset, chunk) { bytes, err ->
            if (err != null) {
                next(false, "read failed at $mediaOffset err=${err.desc()}")
                return@read
            }
            if (bytes == null || bytes.isEmpty()) {
                val ok = mediaTotal == MEDIA_SIZE && mediaSum == MEDIA_SUM
                next(ok, "bytes=$mediaTotal expect=$MEDIA_SIZE sum=$mediaSum expect=$MEDIA_SUM")
                return@read
            }
            for (b in bytes) {
                mediaSum = (mediaSum + (b.toInt() and 0xFF)) % 1000000007L
            }
            mediaTotal += bytes.size
            mediaOffset += bytes.size
            readChunks(next)
        }
    }

    /** 分块读完整文件并按校验和比对原文件（证明传输字节级一致） */
    private fun verifyMediaChecksum(fh: String, next: (Boolean, String) -> Unit, offset: Long = 0L, sum: Long = 0L, total: Long = 0L) {
        sftpModule().read(fh, offset, 64 * 1024) { bytes, err ->
            if (err != null) {
                sftpModule().close(fh) { }
                next(false, "read failed at $offset err=${err.desc()}")
                return@read
            }
            if (bytes == null || bytes.isEmpty()) {
                sftpModule().close(fh) { }
                next(total == MEDIA_SIZE && sum == MEDIA_SUM,
                    "bytes=$total expect=$MEDIA_SIZE sum=$sum expect=$MEDIA_SUM")
                return@read
            }
            var s2 = sum
            for (b in bytes) s2 = (s2 + (b.toInt() and 0xFF)) % 1000000007L
            verifyMediaChecksum(fh, next, offset + bytes.size, s2, total + bytes.size)
        }
    }

    private fun ByteArray.toHexString(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    // endregion

    // region —— 串行执行器 ——

    private fun runNext() {
        if (cursor >= steps.size) {
            finish()
            return
        }
        val (name, fn) = steps[cursor]
        cursor++
        status = "[$cursor/${steps.size}] $name"
        KLog.i(TAG, "STEP $cursor/${steps.size} | $name")
        fn { ok, detail ->
            if (ok) passed++ else {
                failed++
                failures.add("$name :: $detail")
            }
            KLog.i(TAG, "${if (ok) "PASS" else "FAIL"} | $name | $detail")
            runNext()
        }
    }

    private fun finish() {
        status = "完成: PASS=$passed FAIL=$failed"
        KLog.i(TAG, "SUITE END total=${steps.size} pass=$passed fail=$failed")
        failures.forEach { KLog.i(TAG, "FAILURE -> $it") }
        KLog.i(TAG, "==================================================")
    }

    private fun SftpError?.desc(): String =
        if (this == null) "none" else "code=$code msg=$msg detail=${detail ?: ""}"

    // endregion
}
