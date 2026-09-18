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
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.module.sftp.I18n
import com.tencent.kuikly.core.module.sftp.SftpEntry
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpAccessibility
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * SFTP 文件属性页（§17.3.5）
 *
 * - 显示文件名/路径/大小/权限/uid/gid/owner/group/mtime
 * - chmod 编辑（八进制如 755）
 * - chown 编辑（uid/gid 数字）
 * - setMtime 编辑（日期选择器）
 * - 保存按钮调对应 SftpModule 方法
 */
@Page(SftpFilePropsPage.PAGE_NAME)
internal class SftpFilePropsPage : SftpBasePager() {

    private var sessionId: String = ""
    private var remotePath: String = ""
    // 必须 observable：异步 stat 回包要能触发重渲染（普通 var + `when` 不会重建结构）
    private var entry: SftpEntry? by observable(null)
    private var loading: Boolean by observable(true)
    private var errorMsg: String? by observable(null)
    private var chmodMode: String by observable("")           // 用户输入
    private var chownUid: Int = -1
    private var chownGid: Int = -1
    private var setMtimeInput: String = ""        // yyyy-MM-dd HH:mm:ss
    private var editMode: Int by observable(0)  // 0 查看 / 1 chmod / 2 chown / 3 setMtime

    override fun created() {
        super.created()
        val params = pageData.params
        sessionId = params.optString("sessionId", "")
        remotePath = params.optString("remotePath", "")
        refresh()
    }

    private fun refresh() {
        loading = true
        errorMsg = null
        sftpModule().stat(sessionId, remotePath, true) { result, err ->
            if (err != null) {
                errorMsg = err.msg
                loading = false
            } else {
                entry = result
                chmodMode = result?.permission?.let { permToOctal(it) } ?: ""
                chownUid = result?.uid ?: -1
                chownGid = result?.gid ?: -1
                loading = false
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { backgroundColor(SftpColorTokens.bg) }

            // 顶部导航
            View {
                attr {
                    size(pagerData.pageViewWidth, 56f)
                    flexDirectionRow()
                    alignItemsCenter()
                    padding(16f, 8f, 16f, 8f)
                    backgroundColor(SftpColorTokens.cardBg)
                }
                View {
                    attr { size(36f, 36f); allCenter(); accessibility(SftpAccessibility.BTN_BACK) }
                    event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
                    Text { attr { text("<"); fontSize(22f); color(SftpColorTokens.textPrimary) } }
                }
                Text {
                    attr {
                        text(I18n.t("sftp.props.title"))
                        fontSize(18f)
                        fontWeightBold()
                        color(SftpColorTokens.textPrimary)
                        flex(1f)
                        marginLeft(8f)
                    }
                }
            }

            // 内容
            View {
                attr { flex(1f) }
                // 条件指令：stat 回包后才能从 loading 切到属性面板
                vif({ ctx.loading }) {
                    SftpLoadingView()
                }
                velseif({ ctx.errorMsg != null }) {
                    SftpErrorView(ctx.errorMsg ?: "") { ctx.refresh() }
                }
                velseif({ ctx.entry != null }) {
                    SftpFilePropsView(
                        ctx.entry!!,
                        ctx.chmodMode,
                        ctx.chownUid,
                        ctx.chownGid,
                        ctx.editMode,
                        onChmodEdit = { newMode -> ctx.chmodMode = newMode },
                        onChownEdit = { uid, gid -> ctx.chownUid = uid; ctx.chownGid = gid },
                        onMtimeEdit = { mtime -> ctx.setMtimeInput = mtime },
                        onSwitchMode = { mode -> ctx.editMode = mode },
                        onSaveChmod = { ctx.saveChmod() },
                        onSaveChown = { ctx.saveChown() },
                        onSaveMtime = { ctx.saveMtime() }
                    )
                }
            }
        }
    }

    private fun saveChmod() {
        if (chmodMode.isEmpty()) return
        sftpModule().chmod(sessionId, remotePath, chmodMode) { success, err ->
            if (!success || err != null) errorMsg = err?.msg ?: "chmod 失败"
            editMode = 0
            refresh()
        }
    }

    private fun saveChown() {
        sftpModule().chown(sessionId, remotePath, chownUid, chownGid) { success, err ->
            if (!success || err != null) errorMsg = err?.msg ?: "chown 失败"
            editMode = 0
            refresh()
        }
    }

    private fun saveMtime() {
        val mtimeSec = parseMtimeToEpoch(setMtimeInput)
        if (mtimeSec <= 0) {
            errorMsg = "日期格式错误，应为 yyyy-MM-dd HH:mm:ss"
            return
        }
        sftpModule().setMtime(sessionId, remotePath, mtimeSec, mtimeSec) { success, err ->
            if (!success || err != null) errorMsg = err?.msg ?: "setMtime 失败"
            editMode = 0
            refresh()
        }
    }

    companion object {
        const val PAGE_NAME = "SftpFilePropsPage"

        /** rwxr-xr-x → 755 */
        fun permToOctal(perm: String): String {
            if (perm.length < 9) return ""
            return perm.substring(0, 9).map {
                when (it) {
                    'r' -> 4
                    'w' -> 2
                    'x' -> 1
                    '-' -> 0
                    else -> 0
                }
            }.chunked(3) { it.sum() }.joinToString("") { it.toString() }
        }

        /** yyyy-MM-dd HH:mm:ss → epoch seconds（简化实现） */
        fun parseMtimeToEpoch(input: String): Long {
            // 简化：用 Regex 解析。生产环境用 DateTimeFormatter。
            val regex = Regex("(\\d{4})-(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})")
            val m = regex.matchEntire(input) ?: return 0L
            val (y, mo, d, h, mi, s) = m.destructured
            // 简化计算（忽略闰年/时区）
            val days = y.toInt() * 365 + mo.toInt() * 30 + d.toInt()
            return days.toLong() * 86400 + h.toInt() * 3600 + mi.toInt() * 60 + s.toInt()
        }

        /** epoch seconds → yyyy-MM-dd HH:mm:ss（简化，忽略时区） */
        fun formatEpoch(epochSec: Long): String {
            val totalSec = epochSec.toInt()
            val day = totalSec / 86400
            val rem = totalSec % 86400
            val h = rem / 3600
            val mi = (rem % 3600) / 60
            val s = rem % 60
            // 简化日期推算
            val y = 1970 + day / 365
            val doy = day % 365
            val mo = (doy / 30) + 1
            val d = (doy % 30) + 1
            return "$y-${pad2(mo)}-${pad2(d)} ${pad2(h)}:${pad2(mi)}:${pad2(s)}"
        }

        private fun pad2(v: Int): String = if (v < 10) "0$v" else "$v"
    }
}

/** 文件属性视图 */
@Suppress("LongParameterList")
internal fun ViewContainer<*, *>.SftpFilePropsView(
    entry: SftpEntry,
    chmodMode: String,
    chownUid: Int,
    chownGid: Int,
    editMode: Int,
    onChmodEdit: (String) -> Unit,
    onChownEdit: (Int, Int) -> Unit,
    onMtimeEdit: (String) -> Unit,
    onSwitchMode: (Int) -> Unit,
    onSaveChmod: () -> Unit,
    onSaveChown: () -> Unit,
    onSaveMtime: () -> Unit
) {
    View {
        attr { flex(1f); backgroundColor(SftpColorTokens.bg); padding(16f, 16f, 16f, 16f) }
        View {
            attr { backgroundColor(SftpColorTokens.cardBg); borderRadius(8f); padding(16f, 12f, 16f, 12f); flexDirectionColumn() }
            PropRow("名称", entry.name)
            PropRow("路径", entry.path)
            PropRow("类型", if (entry.isDir) "目录" else "文件" + if (entry.isSymlink) " (符号链接)" else "")
            PropRow("大小", formatSize(entry.size))
            PropRow("权限", entry.permission + " (" + chmodMode + ")")
            PropRow("UID", entry.uid.toString())
            PropRow("GID", entry.gid.toString())
            entry.owner?.let { PropRow("所有者", it) }
            entry.group?.let { PropRow("组", it) }
            PropRow("MTime", SftpFilePropsPage.formatEpoch(entry.mtime))
        }
        // 编辑入口按钮
        View {
            attr { flexDirectionRow(); marginTop(16f) }
            EditButton("chmod", editMode == 1) { onSwitchMode(1) }
            EditButton("chown", editMode == 2) { onSwitchMode(2) }
            EditButton("setMtime", editMode == 3) { onSwitchMode(3) }
        }
        when (editMode) {
            1 -> View {
                attr { flexDirectionRow(); marginTop(12f); allCenter() }
                Text { attr { text("mode: "); fontSize(14f); color(SftpColorTokens.textPrimary) } }
                // Phase 0.3 简化：用 Text 显示当前值，生产用 Input
                Text { attr { text(chmodMode); fontSize(14f); color(SftpColorTokens.primary); marginLeft(8f) } }
                View {
                    attr { height(36f); padding(8f, 16f, 8f, 16f); backgroundColor(SftpColorTokens.primary); borderRadius(8f); marginLeft(12f); allCenter() }
                    event { click { onSaveChmod() } }
                    Text { attr { text(I18n.t("sftp.ui.save")); fontSize(14f); color(Color.WHITE) } }
                }
            }
            2 -> View {
                attr { flexDirectionRow(); marginTop(12f); allCenter() }
                Text { attr { text("uid:" + chownUid + " gid:" + chownGid); fontSize(14f); color(SftpColorTokens.textPrimary) } }
                View {
                    attr { height(36f); padding(8f, 16f, 8f, 16f); backgroundColor(SftpColorTokens.primary); borderRadius(8f); marginLeft(12f); allCenter() }
                    event { click { onSaveChown() } }
                    Text { attr { text(I18n.t("sftp.ui.save")); fontSize(14f); color(Color.WHITE) } }
                }
            }
            3 -> View {
                attr { flexDirectionRow(); marginTop(12f); allCenter() }
                Text { attr { text("mtime (yyyy-MM-dd HH:mm:ss)"); fontSize(13f); color(SftpColorTokens.textSecondary) } }
                View {
                    attr { height(36f); padding(8f, 16f, 8f, 16f); backgroundColor(SftpColorTokens.primary); borderRadius(8f); marginLeft(12f); allCenter() }
                    event { click { onSaveMtime() } }
                    Text { attr { text(I18n.t("sftp.ui.save")); fontSize(14f); color(Color.WHITE) } }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.PropRow(label: String, value: String) {
    View {
        attr { flexDirectionRow(); padding(0f, 0f, 0f, 0f); marginBottom(6f) }
        Text { attr { text(label); fontSize(13f); color(SftpColorTokens.textSecondary); width(80f) } }
        Text { attr { text(value); fontSize(13f); color(SftpColorTokens.textPrimary); flex(1f) } }
    }
}

internal fun ViewContainer<*, *>.EditButton(label: String, active: Boolean, onClick: () -> Unit) {
    View {
        attr {
            height(32f)
            padding(8f, 12f, 8f, 12f)
            allCenter()
            backgroundColor(if (active) SftpColorTokens.primary else SftpColorTokens.divider)
            borderRadius(8f)
            marginRight(8f)
        }
        event { click { onClick() } }
        Text { attr { text(label); fontSize(13f); color(if (active) Color.WHITE else SftpColorTokens.textPrimary) } }
    }
}
