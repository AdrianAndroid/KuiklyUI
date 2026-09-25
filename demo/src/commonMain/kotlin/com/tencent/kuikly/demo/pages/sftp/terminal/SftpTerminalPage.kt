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
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.velse
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
    /** 性能：只在缓冲区版本变化时重建行列表（避免每 130ms 无条件 churn） */
    private var lastRenderedVersion: Int = -1
    // 命令历史（条数上限由设置页决定；默认 20）
    private var cmdHistory by observableList<String>()
    private var historyVisible: Boolean by observable(false)
    private var historyMax: Int = 20
    /** 宿主通道（xterm/自动化）输入的行缓冲：遇 \n 记入命令历史 */
    private var hostLineBuffer: String = ""
    private var localMode = false
    private var forceGrid = false
    /** Web/桌面优先用 xterm.js（MIT，成熟终端仿真：ANSI/CJK/方向键/回车）；其它端回退共享网格 */
    private var useXterm: Boolean by observable(false)

    private var shellId: String? = null
    private var offset = 0L
    private var buffer: TerminalBuffer? = null
    private var pollRef: String? = null
    private var readyTimerRef: String? = null
    private var readyDeadline: Long = 0L
    private var xtermId: String = ""

    private var lines: ObservableList<String> by observableList<String>()
    private var status: String by observable("启动中…")
    private var inputText: String by observable("")
    /** shell 是否已就绪（启动期输入先缓冲，避免带偏本地 pty 的 ZLE） */
    private var shellReady: Boolean by observable(false)
    private val pendingSend = ArrayList<String>()
    private val pendingRaw = ArrayList<String>()
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
        // Web/桌面：优先 xterm.js（成熟仿真）；显式 renderer=grid 或宿主不支持时回退共享网格
        useXterm = !forceGrid && runCatching {
            acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).supportsXterm()
        }.getOrDefault(false)
        historyMax = acquireModule<com.tencent.kuikly.core.module.SharedPreferencesModule>(
            com.tencent.kuikly.core.module.SharedPreferencesModule.MODULE_NAME
        ).getInt(KEY_TERM_HISTORY_MAX) ?: 20
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
            // 本地 pty 启动期间若立刻写入会把 shell 的 ZLE 带偏（输入回显但永不执行）。
            // 就绪判定：有输出后安静 [QUIET_MS] 视为就绪，并以 [HARD_READY_MS] 为硬上限（防持续刷屏永不就绪）。
            shellReady = false
            status = "启动中…"
            readyDeadline = com.tencent.kuikly.core.datetime.DateTime.currentTimestamp() + HARD_READY_MS
            scheduleReadyCheck(1200)
            if (useXterm) {
                mountXterm()
            } else {
                buffer = TerminalBuffer(colsCount, rowsCount)
                applyViewportSize()   // 按窗口尺寸设定列宽/行数（尽量贴合可见区域）
            }
            pollOnce()   // 立即首拉，避免依赖定时器首跳
            schedulePoll()
        }
    }

    /** 挂载 xterm.js（绝对定位覆盖网格区域；输入由 xterm onData → terminal_input 回传） */
    private fun mountXterm() {
        if (!useXterm) return
        val bridge = acquireModule<BridgeModule>(BridgeModule.MODULE_NAME)
        val y = pagerData.statusBarHeight + 48f
        val w = pagerData.pageViewWidth
        val h = (pagerData.pageViewHeight - pagerData.statusBarHeight - 48f - 44f).coerceAtLeast(120f)
        bridge.xtermMount(0f, y, w, h, 13f) { termId, cols, rows ->
            if (termId.isEmpty()) {
                // 宿主挂载失败 → 显式回退网格（不静默）
                useXterm = false
                buffer = TerminalBuffer(colsCount, rowsCount)
                applyViewportSize()
                return@xtermMount
            }
            xtermId = termId
            colsCount = cols
            rowsCount = rows
            // 以 xterm 计算出的列/行为准通知 pty
            shellId?.let { terminal.resize(it, cols, rows) }
        }
    }

    /** 就绪判定（可重置）：[delayMs] 内无新输出则视为就绪；不超过硬上限 [readyDeadline] */
    private fun scheduleReadyCheck(delayMs: Int) {
        val now = com.tencent.kuikly.core.datetime.DateTime.currentTimestamp()
        val fireAt = minOf(now + delayMs.toLong(), readyDeadline)
        readyTimerRef?.let { clearTimeout(it) }
        readyTimerRef = setTimeout((fireAt - now).toInt().coerceAtLeast(0)) {
            shellReady = true
            status = if (localMode) "本地终端" else ("远程终端 · " + label.ifEmpty { host })
            flushPendingInput()
        }
    }

    /** shell 就绪后补发启动期间缓冲的输入（先到先发） */
    private fun flushPendingInput() {
        val id = shellId ?: return
        if (pendingRaw.isNotEmpty()) {
            val raws = ArrayList(pendingRaw)
            pendingRaw.clear()
            raws.forEach { writeRaw(it) }
        }
        if (pendingSend.isEmpty()) return
        val cmds = ArrayList(pendingSend)
        pendingSend.clear()
        cmds.forEach { c -> writeLine(id, c) }
    }

    private fun writeLine(id: String, text: String) {
        terminal.write(id, SftpTextLoader.base64(SftpTextLoader.encodeUtf8(text + "\n")))
    }

    /** 原样写入（xterm 字符流） */
    private fun writeRaw(data: String) {
        val id = shellId ?: return
        terminal.write(id, SftpTextLoader.base64(SftpTextLoader.encodeUtf8(data)))
    }

    /** Kuikly 浮层（如命令历史）需要盖在 xterm 之上时，临时隐藏/恢复 xterm */
    private fun setXtermVisible(visible: Boolean) {
        if (!useXterm || xtermId.isEmpty()) return
        runCatching { acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).xtermSetVisible(xtermId, visible) }
    }

    /** 按窗口尺寸计算列数/行数（12.5px 等宽 ≈ 7.5x16），并通知 pty resize；xterm 模式下由 xterm 自己算 */
    private fun applyViewportSize() {
        if (useXterm) return
        val w = pagerData.pageViewWidth
        val h = pagerData.pageViewHeight
        if (w <= 0f || h <= 0f) return
        val newCols = ((w - 16f) / 7.5f).toInt().coerceIn(20, 400)
        val newRows = ((h - 48f - 44f - 12f) / 16f).toInt().coerceIn(5, 200)
        if (newCols == colsCount && newRows == rowsCount) return
        colsCount = newCols
        rowsCount = newRows
        buffer?.resize(newCols, newRows)
        shellId?.let { terminal.resize(it, newCols, newRows) }
        refreshLines(force = true)
    }

    /** 偏移拉取（HTTP 轮询即可，无需 WebSocket） */
    private fun schedulePoll() {
        pollRef = setTimeout(130) { pollOnce(); schedulePoll() }
    }

    /** 单次拉取：无论有无新数据都刷新（自愈，避免丢帧后长时间不更新） */
    private fun pollOnce() {
        val id = shellId ?: return
        terminal.read(id, offset) { b64, next, closed ->
            if (b64.isNotEmpty()) {
                if (useXterm && xtermId.isNotEmpty()) {
                    // xterm 直接消费原始字节流（ANSI/CJK/光标全由它处理）
                    acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).xtermWrite(xtermId, b64)
                } else {
                    buffer?.feed(SftpTextLoader.base64Decode(b64))
                }
                if (!shellReady) scheduleReadyCheck(QUIET_MS)
            }
            if (next > offset) offset = next
            if (!useXterm) refreshLines()
            if (closed) {
                status = "会话已结束"
                shellReady = true
            }
        }
    }

    private fun refreshLines(force: Boolean = false) {
        val b = buffer ?: return
        if (!force && b.version == lastRenderedVersion) return
        lastRenderedVersion = b.version
        val all = b.linesWithScrollback()
        lines.clear()
        lines.addAll(all)
    }

    /** 记录命令历史（去重相邻、超过上限截断） */
    private fun rememberCommand(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty()) return
        val list = cmdHistory.toMutableList()
        if (list.lastOrNull() == c) list.removeAt(list.size - 1)
        list.add(c)
        while (list.size > historyMax) list.removeAt(0)
        cmdHistory.clear()
        cmdHistory.addAll(list)
    }

    /** 原样发送（含回车）；宿主 xterm 直接给字符流。同时按行累积到命令历史。 */
    private fun sendRaw(data: String) {
        if (shellId == null) return
        data.forEach { ch ->
            when (ch) {
                '\n', '\r' -> {
                    if (hostLineBuffer.isNotBlank()) rememberCommand(hostLineBuffer)
                    hostLineBuffer = ""
                }
                '\u007F', '\b' -> if (hostLineBuffer.isNotEmpty()) hostLineBuffer = hostLineBuffer.dropLast(1)
                else -> hostLineBuffer += ch
            }
        }
        if (!shellReady) {
            pendingRaw.add(data)
            return
        }
        writeRaw(data)
    }

    private fun sendInput(text: String) {
        val id = shellId ?: return
        if (text.isEmpty()) return
        rememberCommand(text)
        inputText = ""
        if (!shellReady) {
            // shell 启动中：先排队，就绪后补发（否则本地 pty 会被带偏）
            pendingSend.add(text)
            status = "启动中…（命令已排队）"
            return
        }
        writeLine(id, text)
    }

    override fun pageWillDestroy() {
        super.pageWillDestroy()
        pollRef?.let { clearTimeout(it) }
        readyTimerRef?.let { clearTimeout(it) }
        if (xtermId.isNotEmpty()) {
            runCatching { acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).xtermDispose(xtermId) }
            xtermId = ""
        }
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
                // xterm 模式：由宿主 xterm 覆盖该区域；网格仅作 native 回退
                vif({ !ctx.useXterm }) {
                    TerminalGridView(
                        linesProvider = { ctx.lines },
                        onScrollerReady = { scrollBottom -> ctx.scrollToBottom = scrollBottom },
                    )
                }
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
                            returnKeyTypeSend()
                        }
                        event {
                            textDidChange { s -> ctx.inputText = s.text }
                            // 回车即发送：Web 端 KRTextFieldView 仅在注册 inputReturn 时才绑定 Enter keydown，
                            // 之前只注册 textDidChange → 真实键盘回车无任何反应（只能点「发送」）。
                            inputReturn { p -> ctx.sendInput(if (p.text.isNotEmpty()) p.text else ctx.inputText) }
                        }
                    }
                }
                Text {
                    attr {
                        text("历史")
                        fontSize(13f); color(SftpColorTokens.textSecondary)
                        margin(8f, 4f, 8f, 8f)
                        accessibility("terminal_history_btn")
                    }
                    event { click { ctx.historyVisible = true; ctx.setXtermVisible(false) } }
                }
                Text {
                    attr {
                        text("发送")
                        fontSize(13f); color(SftpColorTokens.primary)
                        margin(8f, 8f, 8f, 8f)
                        accessibility("terminal_send_btn")
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
                                    returnKeyTypeDone()
                                }
                                event {
                                    textDidChange { st -> ctx.loginPwd = st.text }
                                    // 密码框回车 = 连接
                                    inputReturn { ctx.doLocalLogin() }
                                }
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

            // 命令历史弹层（绝对定位；放在内容区之后）
            vif({ ctx.historyVisible }) {
                View {
                    attr {
                        positionAbsolute(); left(0f); top(0f)
                        size(pagerData.pageViewWidth, pagerData.pageViewHeight)
                        backgroundColor(Color(0x99000000))
                        flexDirectionColumn()
                    }
                    event { click { ctx.historyVisible = false; ctx.setXtermVisible(true) } }
                    View { attr { flex(1f) } }
                    View {
                        attr {
                            width(pagerData.pageViewWidth)
                            height(pagerData.pageViewHeight * 0.55f)
                            backgroundColor(SftpColorTokens.cardBg)
                            borderRadius(14f)
                            padding(14f, 12f, 14f, 12f)
                            flexDirectionColumn()
                        }
                        event { click { } }
                        Text {
                            attr {
                                text("命令历史 · 最多 " + ctx.historyMax + " 条")
                                fontSize(14f); fontWeightBold(); color(SftpColorTokens.textPrimary)
                                marginBottom(8f)
                            }
                        }
                        vif({ ctx.cmdHistory.isEmpty() }) {
                            Text { attr { text("暂无历史命令"); fontSize(12.5f); color(SftpColorTokens.textSecondary) } }
                        }
                        velse {
                            Scroller {
                                attr { flex(1f); width(pagerData.pageViewWidth - 28f); flexDirectionColumn() }
                                vfor({ ctx.cmdHistory }) { c ->
                                    Text {
                                        attr {
                                            text(c)
                                            fontSize(12.5f)
                                            color(SftpColorTokens.textPrimary)
                                            fontFamily("monospace")
                                            margin(9f, 8f, 9f, 8f)
                                            backgroundColor(SftpColorTokens.bg)
                                            borderRadius(5f)
                                            marginBottom(4f)
                                        }
                                        event {
                                            click {
                                                ctx.inputText = c
                                                ctx.historyVisible = false
                                                ctx.setXtermVisible(true)
                                            }
                                        }
                                    }
                                }
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
        /** 就绪判定：最后一次输出后安静该时长即视为 shell 就绪 */
        private const val QUIET_MS = 900
        /** 就绪判定硬上限：即使持续刷屏也会在此时间后就绪（防永不就绪） */
        private const val HARD_READY_MS = 5000L
        private const val LOCAL_HOST = "127.0.0.1"
        private const val LOCAL_LABEL = "本机"
        /** 设置页里可调的终端命令历史条数（SharedPreferences key） */
        const val KEY_TERM_HISTORY_MAX = "term_history_max"
    }
}
