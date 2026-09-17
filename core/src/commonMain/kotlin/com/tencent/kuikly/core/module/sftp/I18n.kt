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

/**
 * SFTP 国际化文案（§21.8.9）
 *
 * 中英双语，按 `messageKey` 查询。所有 UI 文案与错误提示都走这里，
 * 便于未来加更多语言（日/韩/法/西等只需补 [messages] 表）。
 *
 * 当前语言由 App 全局配置控制（`I18n.currentLang`），默认 `zh-CN`。
 */
object I18n {
    /** 当前语言 */
    var currentLang: String = "zh-CN"

    /** 文案 key → 各语言文案 */
    private val messages: Map<String, Map<String, String>> = mapOf(
        "sftp.error.network_unreachable" to mapOf(
            "zh-CN" to "网络不可达，请检查网络连接",
            "en-US" to "Network unreachable, please check your connection"
        ),
        "sftp.error.connection_timeout" to mapOf(
            "zh-CN" to "连接超时，请稍后重试",
            "en-US" to "Connection timeout, please retry"
        ),
        "sftp.error.auth_failed" to mapOf(
            "zh-CN" to "认证失败，请检查用户名和密码",
            "en-US" to "Authentication failed, check username and password"
        ),
        "sftp.error.host_key_mismatch" to mapOf(
            "zh-CN" to "服务器指纹与之前记录不一致，可能存在安全风险",
            "en-US" to "Server host key mismatch, possible security risk"
        ),
        "sftp.error.host_key_rejected" to mapOf(
            "zh-CN" to "服务器指纹被拒绝",
            "en-US" to "Server host key rejected"
        ),
        "sftp.error.server_refused" to mapOf(
            "zh-CN" to "服务器拒绝连接",
            "en-US" to "Server refused connection"
        ),
        "sftp.error.connection_reset" to mapOf(
            "zh-CN" to "连接被重置",
            "en-US" to "Connection reset by peer"
        ),
        "sftp.error.idle_disconnected" to mapOf(
            "zh-CN" to "会话空闲超时已断开",
            "en-US" to "Session idle timeout disconnected"
        ),
        "sftp.error.reconnect_failed" to mapOf(
            "zh-CN" to "自动重连失败，请手动重试",
            "en-US" to "Auto-reconnect failed, please retry manually"
        ),
        "sftp.error.permission_denied" to mapOf(
            "zh-CN" to "权限不足",
            "en-US" to "Permission denied"
        ),
        "sftp.error.operation_not_permitted" to mapOf(
            "zh-CN" to "操作不被允许",
            "en-US" to "Operation not permitted"
        ),
        "sftp.error.no_such_file" to mapOf(
            "zh-CN" to "文件或目录不存在",
            "en-US" to "No such file or directory"
        ),
        "sftp.error.no_such_path" to mapOf(
            "zh-CN" to "路径不存在",
            "en-US" to "No such path"
        ),
        "sftp.error.dir_not_empty" to mapOf(
            "zh-CN" to "目录非空，需递归删除",
            "en-US" to "Directory not empty, recursive delete required"
        ),
        "sftp.error.disk_full" to mapOf(
            "zh-CN" to "磁盘空间不足",
            "en-US" to "Disk full"
        ),
        "sftp.error.file_exists" to mapOf(
            "zh-CN" to "文件已存在",
            "en-US" to "File already exists"
        ),
        "sftp.error.symlink_loop" to mapOf(
            "zh-CN" to "符号链接循环",
            "en-US" to "Symlink loop detected"
        ),
        "sftp.error.read_only_fs" to mapOf(
            "zh-CN" to "只读文件系统",
            "en-US" to "Read-only filesystem"
        ),
        "sftp.error.pdf_password_required" to mapOf(
            "zh-CN" to "PDF 需要密码",
            "en-US" to "PDF password required"
        ),
        "sftp.error.preview_too_large" to mapOf(
            "zh-CN" to "文件过大，不支持预览，建议下载",
            "en-US" to "File too large to preview, suggest download"
        ),
        "sftp.error.binary_not_previewable" to mapOf(
            "zh-CN" to "二进制文件，不支持预览，是否下载？",
            "en-US" to "Binary file, cannot preview. Download instead?"
        ),
        "sftp.error.preview_timeout" to mapOf(
            "zh-CN" to "预览加载超时",
            "en-US" to "Preview load timeout"
        ),
        "sftp.error.preview_concurrency_limit" to mapOf(
            "zh-CN" to "预览实例数已达上限（3）",
            "en-US" to "Preview instances reached limit (3)"
        ),
        "sftp.error.proxy_start_failed" to mapOf(
            "zh-CN" to "无法启动本地代理",
            "en-US" to "Failed to start local proxy"
        ),
        "sftp.error.proxy_port_conflict" to mapOf(
            "zh-CN" to "本地代理端口被占用",
            "en-US" to "Local proxy port in use"
        ),
        "sftp.error.token_expired" to mapOf(
            "zh-CN" to "会话已过期，正在重新加载",
            "en-US" to "Session expired, reloading"
        ),
        "sftp.error.proxy_read_failed" to mapOf(
            "zh-CN" to "读取数据失败",
            "en-US" to "Read failed"
        ),
        "sftp.error.cancelled" to mapOf(
            "zh-CN" to "操作已取消",
            "en-US" to "Operation cancelled"
        ),
        "sftp.error.timeout" to mapOf(
            "zh-CN" to "操作超时",
            "en-US" to "Operation timeout"
        ),
        "sftp.error.not_implemented" to mapOf(
            "zh-CN" to "功能尚未实现",
            "en-US" to "Not implemented"
        ),
        "sftp.error.unknown" to mapOf(
            "zh-CN" to "未知错误",
            "en-US" to "Unknown error"
        ),
        // —— UI 通用文案 ——
        "sftp.ui.loading" to mapOf(
            "zh-CN" to "加载中...",
            "en-US" to "Loading..."
        ),
        "sftp.ui.empty" to mapOf(
            "zh-CN" to "暂无数据",
            "en-US" to "No data"
        ),
        "sftp.ui.error" to mapOf(
            "zh-CN" to "出错了",
            "en-US" to "Error"
        ),
        "sftp.ui.retry" to mapOf(
            "zh-CN" to "重试",
            "en-US" to "Retry"
        ),
        "sftp.ui.cancel" to mapOf(
            "zh-CN" to "取消",
            "en-US" to "Cancel"
        ),
        "sftp.ui.confirm" to mapOf(
            "zh-CN" to "确认",
            "en-US" to "Confirm"
        ),
        "sftp.ui.delete" to mapOf(
            "zh-CN" to "删除",
            "en-US" to "Delete"
        ),
        "sftp.ui.download" to mapOf(
            "zh-CN" to "下载",
            "en-US" to "Download"
        ),
        "sftp.ui.connect" to mapOf(
            "zh-CN" to "连接",
            "en-US" to "Connect"
        ),
        "sftp.ui.disconnect" to mapOf(
            "zh-CN" to "断开",
            "en-US" to "Disconnect"
        ),
        "sftp.ui.test_connection" to mapOf(
            "zh-CN" to "测试连接",
            "en-US" to "Test Connection"
        ),
        "sftp.ui.clear_confirm" to mapOf(
            "zh-CN" to "确认清空？此操作不可撤销",
            "en-US" to "Confirm clear? This cannot be undone"
        ),
        "sftp.ui.continue_play" to mapOf(
            "zh-CN" to "上次观看至 %s，是否继续？",
            "en-US" to "Last watched at %s, continue?"
        ),
        "sftp.ui.continue" to mapOf(
            "zh-CN" to "继续",
            "en-US" to "Continue"
        ),
        "sftp.ui.from_start" to mapOf(
            "zh-CN" to "从头开始",
            "en-US" to "From start"
        ),
        "sftp.ui.next_episode_countdown" to mapOf(
            "zh-CN" to "下一集将在 %d 秒后播放",
            "en-US" to "Next episode in %d seconds"
        ),
        "sftp.ui.is_last_episode" to mapOf(
            "zh-CN" to "已是最后一集",
            "en-US" to "This is the last episode"
        ),
        "sftp.ui.completed" to mapOf(
            "zh-CN" to "✓ 已看完",
            "en-US" to "✓ Watched"
        ),
        "sftp.ui.first_frame_timeout" to mapOf(
            "zh-CN" to "加载超时，请检查网络或文件格式",
            "en-US" to "Load timeout, check network or file format"
        ),
        "sftp.ui.codec_unsupported" to mapOf(
            "zh-CN" to "设备不支持此编码",
            "en-US" to "Codec not supported on this device"
        )
    )

    /** 按 key + 当前语言取文案；找不到返回 key 本身 */
    fun t(key: String): String = messages[key]?.get(currentLang) ?: key

    /** 按 key + 指定语言取文案 */
    fun t(key: String, lang: String): String = messages[key]?.get(lang) ?: key

    /**
     * 带参数格式化（按当前语言）。
     * commonMain 无 `String.format`，手动替换 `%s` / `%d` 占位符。
     */
    fun t(key: String, vararg args: Any?): String {
        val template = t(key)
        if (args.isEmpty()) return template
        var result = template
        var argIndex = 0
        val regex = Regex("%[sd]")
        var lastEnd = 0
        val sb = StringBuilder()
        regex.findAll(template).forEach { match ->
            sb.append(template, lastEnd, match.range.first)
            if (argIndex < args.size) {
                sb.append(args[argIndex].toString())
                argIndex++
            } else {
                sb.append(match.value)
            }
            lastEnd = match.range.last + 1
        }
        sb.append(template, lastEnd, template.length)
        result = sb.toString()
        return result
    }

    /** 切换语言 */
    fun setLang(lang: String) {
        currentLang = lang
    }

    /** 支持的语言列表 */
    fun supportedLangs(): List<String> = listOf("zh-CN", "en-US")
}
