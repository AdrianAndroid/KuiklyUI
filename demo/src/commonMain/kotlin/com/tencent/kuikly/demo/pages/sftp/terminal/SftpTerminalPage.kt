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
package com.tencent.kuikly.demo.pages.sftp.terminal

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.base.BridgeModule
import com.tencent.kuikly.demo.pages.sftp.SftpBasePager
import com.tencent.kuikly.demo.pages.sftp.SftpPageNames
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens
import com.tencent.kuikly.demo.pages.sftp.viewer.SftpTextLoader

/**
 * 终端页（**跨端**）：
 * - Web/桌面：宿主 xterm.js（如可用，性能更好）
 * - 其它端/降级：`TerminalBuffer` + `TerminalGridView`（commonMain 纯 Kotlin，六端共用）
 * - shell 通道：`KRTerminalModule`（web → 本地网关；native → 待接入 libssh2 pty）
 *
 * 页面参数：`local`（true=本地终端）、`sessionId`、`connectionLabel`、`host`、`renderer`(auto|grid)
 */
@Page(SftpTerminalPage.PAGE_NAME)
internal class SftpTerminalPage : SftpBasePager() {

    // 必须经 acquireModule 绑定到 Pager（直接 new 不会挂到渲染桥，回调不会回来）
    private val terminal: TerminalModule
        get() = acquireModule<TerminalModule>(TerminalModule.MODULE_NAME)
    private var sessionId = ""
    private var label = ""
    private var host = ""
    private var connectionId = ""
    // 本地终端 = SSH 本机；无保存凭据时先提示输入账号密码（可记住）
    private var needLogin: Boolean by observable(false)
    private var loginUser: String by observable("")
    private var loginPwd: String by observable("")
    private var rememberPwd: Boolean by observable(true)
    private var loginError: String by observable("")
    private var pollTicks: Int = 0
    private var localMode = false
    private var forceGrid = false

    private var shellId: String? = null
    private var offset = 0L
    private var buffer: TerminalBuffer? = null
    private var pollRef: String? = null
    private var xtermId: String = ""

    private var lines: ObservableList<String> by observableList<String>()
    private var status: String by observable("启动中…")
    private var inputText: String by observable("")
    private var rowsCount: Int by observable(24)
    private var colsCount: Int by observable(80)

    /** 宿主 → 页面的终端输入通道（xterm.js 的 onData，以及自动化测试都用它） */
    private val hostEventObserver = object : IPagerEventObserver {
        override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
            if (pagerEvent == EVENT_TERMINAL_INPUT) {
                val data = eventData.optString("data")
                if (data.isNotEmpty()) sendRaw(data)
            }
        }
    }

    override fun created() {
        super.created()
        addPagerEventObserver(hostEventObserver)
        val p = pageData.params
        localMode = p.optBoolean("local", false)
        sessionId = p.optString("sessionId", "")
        label = p.optString("connectionLabel", "")
        host = p.optString("host", "")
        connectionId = p.optString("connectionId", "")
        forceGrid = p.optString("renderer", "auto") == "grid"
        colsCount = 80
        rowsCount = 22
        start()
    }

    private fun start() {
        val bridge = acquireModule<BridgeModule>(BridgeModule.MODULE_NAME)
        val supported = runCatching { bridge.supportsTerminal() }.getOrDefault(false)
        if (!supported) {
            // 未实现端（如 Android/iOS 尚未接 libssh2 pty）：显式不可用，不调用未实现方法
            status = "本端暂不支持终端（待接入原生 shell）"
            return
        }
        if (localMode && sessionId.isEmpty()) {
            // 已保存过「本机」凭据则直接连（做到「记住账号密码」）
            sftpConnectionModule().list { items, _ ->
                val saved = items.firstOrNull { it.label == LOCAL_LABEL || it.host == LOCAL_HOST }
                if (saved != null) {
                    status = "连接本机…"
                    connectHost(saved.host, saved.port, saved.user, saved.password ?: "")
                } else {
                    needLogin = true
                    loginUser = if (loginUser.isEmpty()) "zhaojian" else loginUser   // 预填本机账号（密码手输一次后可记住）
                    status = "本机 SSH 登录"
                }
            }
            return
        }
        if (!localMode && sessionId.isEmpty()) {
            // 从连接库解析凭据并连接（凭据不进 URL）
            status = "连接中…"
            sftpConnectionModule().get(connectionId) { conn, cerr ->
                if (conn == null) {
                    status = "连接不存在：" + (cerr?.msg ?: "")
                    return@get
                }
                label = conn.label
                host = conn.host
                sftpModule().connect(
                    com.tencent.kuikly.core.module.sftp.SftpConnectParam(
                        host = conn.host, port = conn.port, user = conn.user, password = conn.password,
                        privateKey = conn.privateKey, passphrase = conn.passphrase, authMethod = conn.authMethod,
                    ),
                ) { sid, err ->
                    if (sid == null) {
                        status = "连接失败：" + (err?.msg ?: "")
                        return@connect
                    }
                    sessionId = sid
                    openShell()
                }
            }
            return
        }
        openShell()
    }

    private fun cancelLogin() {
        needLogin = false
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
    }

    /** 账号密码登录本机（可记住） */
    private fun doLocalLogin() {
        val u = loginUser.trim()
        val pw = loginPwd
        if (u.isEmpty() || pw.isEmpty()) {
            loginError = "请输入账号与密码"
            return
        }
        loginError = ""
        val host = LOCAL_HOST
        val port = 22
        if (rememberPwd) {
            sftpConnectionModule().add(
                com.tencent.kuikly.core.module.sftp.SftpConnection(
                    id = com.tencent.kuikly.core.module.sftp.SftpConnection.buildId(host, port, u),
                    label = LOCAL_LABEL,
                    host = host,
                    port = port,
                    user = u,
                    password = pw,
                )
            ) { _, _ -> }
        }
        connectHost(host, port, u, pw)
    }

    private fun connectHost(host: String, port: Int, user: String, password: String) {
        sftpModule().connect(
            com.tencent.kuikly.core.module.sftp.SftpConnectParam(
                host = host, port = port, user = user, password = password,
            )
        ) { sid, err ->
            if (sid == null) {
                loginError = err?.msg ?: "连接失败"
                needLogin = true
                status = "本机 SSH 登录"
                return@connect
            }
            sessionId = sid
            label = LOCAL_LABEL
            this.host = host
            openShell()
        }
    }

    private fun openShell() {
        terminal.open(local = localMode, sessionId = sessionId, cols = colsCount, rows = rowsCount) { id, err ->
            if (id == null) {
                status = "打开失败：" + (err ?: "未知错误")
                return@open
            }
            shellId = id
            buffer = TerminalBuffer(colsCount, rowsCount)
            status = if (localMode) "本地终端" else ("远程终端 · " + label.ifEmpty { host })
            pollOnce()   // 立即首拉，避免依赖定时器首跳
            schedulePoll()
        }
    }

    /** 偏移拉取（HTTP 轮询即可，无需 WebSocket） */
    private fun schedulePoll() {
        pollRef = setTimeout(130) { pollOnce(); schedulePoll() }
    }

    /** 单次拉取：无论有无新数据都刷新（自愈，避免丢帧后长时间不更新） */
    private fun pollOnce() {
        val id = shellId ?: return
        pollTicks++
        terminal.read(id, offset) { b64, next, closed ->
            status = (if (localMode) "本地终端" else "远程终端") + " · #" + pollTicks
            if (b64.isNotEmpty()) {
                buffer?.feed(SftpTextLoader.base64Decode(b64))
            }
            if (next > offset) offset = next
            refreshLines()
            if (closed && status != "会话已结束") status = "会话已结束"
        }
    }

    private fun refreshLines() {
        val b = buffer ?: return
        val all = b.allLines()
        lines.clear()
        lines.addAll(all)
    }

    /** 原样发送（含回车）；宿主 xterm 直接给字符流 */
    private fun sendRaw(data: String) {
        val id = shellId ?: return
        terminal.write(id, SftpTextLoader.base64(SftpTextLoader.encodeUtf8(data)))
    }

    private fun sendInput(text: String) {
        val id = shellId ?: return
        if (text.isEmpty()) return
        terminal.write(id, SftpTextLoader.base64(SftpTextLoader.encodeUtf8(text + "\n")))
        inputText = ""
    }

    override fun pageWillDestroy() {
        super.pageWillDestroy()
        pollRef?.let { clearTimeout(it) }
        shellId?.let { terminal.close(it) }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { backgroundColor(Color(0xFF0B0B0B)) }
            // 顶部：标题 + 状态 + 关闭
            View {
                attr {
                    size(pagerData.pageViewWidth, 48f)
                    flexDirectionRow(); alignItemsCenter()
                    padding(12f, 6f, 12f, 6f)
                    backgroundColor(SftpColorTokens.cardBg)
                }
                Text {
                    attr { text("<"); fontSize(20f); color(SftpColorTokens.textPrimary); size(30f, 30f) }
                    event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
                }
                Text {
                    attr {
                        text("终端")
                        fontSize(14f); fontWeightBold(); color(SftpColorTokens.textPrimary)
                        marginLeft(6f)
                    }
                }
                Text {
                    attr {
                        text(ctx.status)
                        fontSize(11f); color(SftpColorTokens.textSecondary)
                        flex(1f); marginLeft(8f); lines(1)
                    }
                }
            }

            // 终端区（共享网格渲染；xterm 可用时由宿主覆盖在同类区域上）
            View {
                attr { flex(1f); flexDirectionColumn() }
                TerminalGridView(
                    linesProvider = { ctx.lines },
                    onScrollerReady = { scrollBottom -> ctx.scrollToBottom = scrollBottom },
                )
            }

            // 输入行（跨端可用；xterm 模式下也可用这里输入）
            View {
                attr {
                    size(pagerData.pageViewWidth, 44f)
                    flexDirectionRow(); alignItemsCenter()
                    padding(10f, 6f, 10f, 6f)
                    backgroundColor(SftpColorTokens.cardBg)
                }
                View {
                    attr {
                        flex(1f); height(32f)
                        backgroundColor(SftpColorTokens.bg); borderRadius(6f)
                        padding(8f, 6f, 8f, 6f)
                    }
                    Input {
                        attr {
                            text(ctx.inputText)
                            placeholder("输入命令后回车…")
                            fontSize(12.5f); height(20f); flex(1f)
                            color(SftpColorTokens.textPrimary)
                        }
                        event { textDidChange { s -> ctx.inputText = s.text } }
                    }
                }
                Text {
                    attr {
                        text("发送")
                        fontSize(13f); color(SftpColorTokens.primary)
                        margin(8f, 8f, 8f, 8f)
                    }
                    event { click { ctx.sendInput(ctx.inputText) } }
                }
            }

            // 本地终端登录弹窗（不污染终端区域；绝对定位盖在上面）
            vif({ ctx.needLogin }) {
                View {
                    attr {
                        positionAbsolute(); left(0f); top(0f)
                        size(pagerData.pageViewWidth, pagerData.pageViewHeight)
                        backgroundColor(Color(0x99000000))
                        allCenter()
                    }
                    View {
                        attr {
                            width(pagerData.pageViewWidth - 48f)
                            backgroundColor(SftpColorTokens.cardBg)
                            borderRadius(12f)
                            padding(16f, 14f, 16f, 14f)
                            flexDirectionColumn()
                        }
                        Text {
                            attr {
                                text("登录本机（SSH 127.0.0.1）")
                                fontSize(15f); fontWeightBold(); color(SftpColorTokens.textPrimary)
                                marginBottom(10f)
                            }
                        }
                        View {
                            attr {
                                height(38f); backgroundColor(SftpColorTokens.bg); borderRadius(6f)
                                padding(8f, 8f, 8f, 8f); marginBottom(8f)
                            }
                            Input {
                                attr {
                                    text(ctx.loginUser); placeholder("账号")
                                    fontSize(13f); height(22f); flex(1f); color(SftpColorTokens.textPrimary)
                                }
                                event { textDidChange { st -> ctx.loginUser = st.text } }
                            }
                        }
                        View {
                            attr {
                                height(38f); backgroundColor(SftpColorTokens.bg); borderRadius(6f)
                                padding(8f, 8f, 8f, 8f); marginBottom(8f)
                            }
                            Input {
                                attr {
                                    text(ctx.loginPwd); placeholder("密码")
                                    fontSize(13f); height(22f); flex(1f); color(SftpColorTokens.textPrimary)
                                    keyboardTypePassword()
                                }
                                event { textDidChange { st -> ctx.loginPwd = st.text } }
                            }
                        }
                        Text {
                            attr {
                                text(if (ctx.rememberPwd) "☑ 记住账号密码（下次直接连）" else "☐ 记住账号密码")
                                fontSize(12.5f); color(SftpColorTokens.textSecondary)
                                marginBottom(10f)
                            }
                            event { click { ctx.rememberPwd = !ctx.rememberPwd } }
                        }
                        vif({ ctx.loginError.isNotEmpty() }) {
                            Text {
                                attr {
                                    text(ctx.loginError)
                                    fontSize(12f); color(SftpColorTokens.danger)
                                    marginBottom(8f)
                                }
                            }
                        }
                        View {
                            attr { flexDirectionRow(); alignItemsCenter() }
                            View { attr { flex(1f) } }
                            Text {
                                attr { text("取消"); fontSize(14f); color(SftpColorTokens.textSecondary); margin(8f, 8f, 8f, 8f) }
                                event { click { ctx.cancelLogin() } }
                            }
                            Text {
                                attr { text("连接"); fontSize(14f); color(SftpColorTokens.primary); margin(8f, 8f, 8f, 8f) }
                                event { click { ctx.doLocalLogin() } }
                            }
                        }
                    }
                }
            }
        }
    }

    private var scrollToBottom: (() -> Unit)? = null

    companion object {
        const val PAGE_NAME = SftpPageNames.TERMINAL
        /** 宿主输入事件名（与 h5App/kr-terminal.js 的 __kuiklySendEvent__ 一致） */
        private const val EVENT_TERMINAL_INPUT = "terminal_input"
        private const val LOCAL_HOST = "127.0.0.1"
        private const val LOCAL_LABEL = "本机"
    }
}
